package com.syncsphere.collaboration;

import com.syncsphere.config.SyncSphereProperties;
import com.syncsphere.document.DocumentService;
import com.syncsphere.document.DocumentSnapshot;
import com.syncsphere.sync.CursorMovedEvent;
import com.syncsphere.sync.DocumentUpdatedEvent;
import com.syncsphere.sync.PresenceChangedEvent;
import com.syncsphere.sync.SyncEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates one collaboration action at a time: call the document/presence logic, then publish the
 * resulting event. It knows nothing about STOMP headers or sessions (that is the controller's job).
 *
 * <p><b>Ordering rule:</b> an edit is published only AFTER {@link DocumentService#applyEdit} has returned,
 * i.e. after its database transaction committed. Publishing inside the transaction could announce an edit
 * that is later rolled back. (This method is deliberately not {@code @Transactional}.)
 */
@Service
public class CollaborationService {

    private static final Logger log = LoggerFactory.getLogger(CollaborationService.class);

    /** Colours are assigned by the server from the participant ID, so clients cannot impersonate a colour. */
    private static final List<String> PARTICIPANT_COLORS = List.of(
            "#e6194b", "#3cb44b", "#4363d8", "#f58231", "#911eb4", "#008080", "#9a6324", "#800000");

    private final DocumentService documentService;
    private final PresenceService presenceService;
    private final SyncEventPublisher publisher;
    private final String instanceId;

    public CollaborationService(DocumentService documentService, PresenceService presenceService,
                                SyncEventPublisher publisher, SyncSphereProperties properties) {
        this.documentService = documentService;
        this.presenceService = presenceService;
        this.publisher = publisher;
        this.instanceId = properties.instanceId();
    }

    /**
     * Registers the participant and returns the snapshot the client should start from.
     * Throws DocumentNotFoundException before touching presence if the document does not exist.
     */
    public JoinedMessage join(UUID documentId, String participantId, String displayName) {
        DocumentSnapshot snapshot = documentService.getDocument(documentId);
        Participant participant = new Participant(
                participantId, displayName, colorFor(participantId), instanceId, Instant.now());

        List<Participant> participants;
        try {
            presenceService.join(documentId, participant);
            participants = presenceService.listParticipants(documentId);
        } catch (DataAccessException e) {
            // Presence is a "nice to have": without Redis the user can still load and edit the document.
            log.warn("Presence unavailable, continuing without it documentId={} cause={}", documentId, e.getMessage());
            participants = List.of(participant);
        }

        publisher.publish(PresenceChangedEvent.of(documentId, PresenceChangedEvent.Change.JOINED, participant, instanceId));
        log.info("Participant joined documentId={} participantId={} name=\"{}\" revision={}",
                documentId, participantId, displayName, snapshot.revision());
        return new JoinedMessage(snapshot, participant, participants);
    }

    /**
     * Persists the edit (revision-checked) and broadcasts the new content to every instance.
     * A RevisionConflictException propagates to the controller, which tells only the sender.
     */
    public DocumentSnapshot submitEdit(UUID documentId, Participant editor, EditRequest request) {
        DocumentSnapshot updated = documentService.applyEdit(documentId, request.baseRevision(), request.content());
        // The transaction has committed at this point; only now is it safe to announce the change.
        publisher.publish(DocumentUpdatedEvent.of(updated, editor, request.clientEditId(), instanceId));
        log.info("Edit accepted documentId={} participantId={} revision={} chars={}",
                documentId, editor.participantId(), updated.revision(), updated.content().length());
        return updated;
    }

    public void moveCursor(UUID documentId, Participant participant, CursorRequest request) {
        publisher.publish(CursorMovedEvent.of(documentId, participant, request.revision(),
                request.selectionStart(), request.selectionEnd(), instanceId));
    }

    public void leave(UUID documentId, Participant participant) {
        boolean wasPresent;
        try {
            wasPresent = presenceService.leave(documentId, participant.participantId());
        } catch (DataAccessException e) {
            log.warn("Presence unavailable during leave documentId={} cause={}", documentId, e.getMessage());
            wasPresent = true; // we cannot tell, so announce the departure anyway
        }
        if (wasPresent) {
            publisher.publish(PresenceChangedEvent.of(documentId, PresenceChangedEvent.Change.LEFT, participant, instanceId));
            log.info("Participant left documentId={} participantId={}", documentId, participant.participantId());
        }
    }

    static String colorFor(String participantId) {
        return PARTICIPANT_COLORS.get(Math.floorMod(participantId.hashCode(), PARTICIPANT_COLORS.size()));
    }
}
