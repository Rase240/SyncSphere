package com.syncsphere.collaboration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.syncsphere.document.DocumentSnapshot;

import java.util.List;
import java.util.UUID;

/**
 * Sent privately to a client (on {@code /user/queue/errors}) when one of its STOMP messages could not be
 * processed. The connection stays open; only that one message was rejected.
 *
 * @param code    machine-readable reason, see {@link Code}
 * @param details per-field validation messages, when relevant
 * @param latest  for REVISION_CONFLICT: the current authoritative document, so the client can recover
 *                without a separate HTTP request
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorMessage(Code code, String message, UUID documentId, List<String> details, DocumentSnapshot latest) {

    public enum Code {
        REVISION_CONFLICT,
        VALIDATION_FAILED,
        INVALID_MESSAGE,
        DOCUMENT_NOT_FOUND,
        NOT_JOINED,
        SERVICE_UNAVAILABLE,
        INTERNAL_ERROR
    }

    public static ErrorMessage of(Code code, String message) {
        return new ErrorMessage(code, message, null, null, null);
    }
}
