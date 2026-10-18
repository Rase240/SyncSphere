package com.syncsphere.collaboration;

import com.syncsphere.document.DocumentNotFoundException;
import com.syncsphere.document.DocumentService;
import com.syncsphere.document.DocumentSnapshot;
import com.syncsphere.document.RevisionConflictException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP entry point. A browser SENDs to {@code /app/documents/{id}/join|edit|cursor}; Spring strips the
 * {@code /app} prefix and routes the frame to the matching {@code @MessageMapping} method below.
 *
 * <p>Responsibilities of this class: extract the session/principal, enforce "join before edit", and turn
 * exceptions into private error messages. The actual work is delegated to {@link CollaborationService}.
 *
 * <p>Methods annotated with {@code @SendToUser} return a value that Spring sends only to the user (connection)
 * that sent the original message, at {@code /user/queue/...}.
 */
@Controller
public class CollaborationController {

    private static final Logger log = LoggerFactory.getLogger(CollaborationController.class);
    private static final String JOINED_DOCUMENT_ATTRIBUTE = "syncsphere.joinedDocument";

    private final CollaborationService collaborationService;
    private final DocumentService documentService;

    public CollaborationController(CollaborationService collaborationService, DocumentService documentService) {
        this.collaborationService = collaborationService;
        this.documentService = documentService;
    }

    @MessageMapping("/documents/{documentId}/join")
    @SendToUser(destinations = "/queue/snapshot", broadcast = false)
    public JoinedMessage join(@DestinationVariable UUID documentId,
                              @Valid @Payload JoinRequest request,
                              Principal principal,
                              SimpMessageHeaderAccessor headers) {
        Map<String, Object> session = sessionAttributes(headers);

        // One connection edits one document at a time; joining another one leaves the previous document.
        JoinedDocument previous = (JoinedDocument) session.remove(JOINED_DOCUMENT_ATTRIBUTE);
        if (previous != null) {
            collaborationService.leave(previous.documentId(), previous.participant());
        }

        JoinedMessage joined = collaborationService.join(documentId, principal.getName(), request.displayName().strip());
        session.put(JOINED_DOCUMENT_ATTRIBUTE, new JoinedDocument(documentId, joined.you()));
        return joined;
    }

    @MessageMapping("/documents/{documentId}/edit")
    public void edit(@DestinationVariable UUID documentId,
                     @Valid @Payload EditRequest request,
                     SimpMessageHeaderAccessor headers) {
        JoinedDocument joined = requireJoined(headers, documentId);
        collaborationService.submitEdit(documentId, joined.participant(), request);
    }

    @MessageMapping("/documents/{documentId}/cursor")
    public void cursor(@DestinationVariable UUID documentId,
                       @Valid @Payload CursorRequest request,
                       SimpMessageHeaderAccessor headers) {
        JoinedDocument joined = requireJoined(headers, documentId);
        collaborationService.moveCursor(documentId, joined.participant(), request);
    }

    /**
     * Fired when a WebSocket closes for any reason: tab closed, network lost, or missed STOMP heartbeats.
     * {@code remove} makes this idempotent, because Spring may fire the event more than once per session.
     */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Map<String, Object> session = StompHeaderAccessor.wrap(event.getMessage()).getSessionAttributes();
        if (session == null) {
            return;
        }
        JoinedDocument joined = (JoinedDocument) session.remove(JOINED_DOCUMENT_ATTRIBUTE);
        if (joined != null) {
            collaborationService.leave(joined.documentId(), joined.participant());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Error handling: each handler's return value is sent privately to the client that caused it.
    // ---------------------------------------------------------------------------------------------

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorMessage handleConflict(RevisionConflictException ex) {
        DocumentSnapshot latest = documentService.getDocument(ex.getDocumentId());
        return new ErrorMessage(ErrorMessage.Code.REVISION_CONFLICT,
                "Your edit was based on revision " + ex.getBaseRevision()
                        + " but the document is at revision " + latest.revision() + ".",
                ex.getDocumentId(), null, latest);
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorMessage handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult() == null ? List.of()
                : ex.getBindingResult().getAllErrors().stream()
                        .map(error -> error.getDefaultMessage())
                        .toList();
        return new ErrorMessage(ErrorMessage.Code.VALIDATION_FAILED, "Message validation failed", null, details, null);
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorMessage handleUnreadable(MessageConversionException ex) {
        return ErrorMessage.of(ErrorMessage.Code.INVALID_MESSAGE, "Message body is not valid JSON for this destination");
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorMessage handleNotFound(DocumentNotFoundException ex) {
        return new ErrorMessage(ErrorMessage.Code.DOCUMENT_NOT_FOUND, ex.getMessage(), ex.getDocumentId(), null, null);
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorMessage handleNotJoined(NotJoinedException ex) {
        return ErrorMessage.of(ErrorMessage.Code.NOT_JOINED, ex.getMessage());
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorMessage handleDataAccess(DataAccessException ex) {
        log.error("Data store unavailable while handling STOMP message", ex);
        return ErrorMessage.of(ErrorMessage.Code.SERVICE_UNAVAILABLE, "A data store is temporarily unavailable. Please retry.");
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorMessage handleUnexpected(Exception ex) {
        log.error("Unexpected error while handling STOMP message", ex);
        return ErrorMessage.of(ErrorMessage.Code.INTERNAL_ERROR, "Unexpected server error");
    }

    private static JoinedDocument requireJoined(SimpMessageHeaderAccessor headers, UUID documentId) {
        JoinedDocument joined = (JoinedDocument) sessionAttributes(headers).get(JOINED_DOCUMENT_ATTRIBUTE);
        if (joined == null || !joined.documentId().equals(documentId)) {
            throw new NotJoinedException(documentId);
        }
        return joined;
    }

    private static Map<String, Object> sessionAttributes(SimpMessageHeaderAccessor headers) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        if (attributes == null) {
            throw new IllegalStateException("STOMP message without WebSocket session attributes");
        }
        return attributes;
    }
}
