package com.syncsphere.collaboration;

import com.syncsphere.document.Document;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * STOMP payload sent to {@code /app/documents/{documentId}/edit}.
 *
 * @param baseRevision the revision the client's text was based on. The server never trusts it blindly:
 *                     it is compared with the stored revision, and a mismatch is rejected as a conflict.
 * @param content      the complete new document text (whole-document replacement, not a diff)
 * @param clientEditId a client-generated ID echoed back in the resulting DocumentUpdatedEvent, so the
 *                     client can tell "my edit was accepted" apart from "someone else edited"
 */
public record EditRequest(
        @NotNull(message = "baseRevision is required")
        @PositiveOrZero(message = "baseRevision must be >= 0")
        Long baseRevision,

        @NotNull(message = "content is required")
        @Size(max = Document.MAX_CONTENT_LENGTH, message = "content must be at most 50000 characters")
        @Pattern(regexp = Document.NO_NUL_CHARACTERS, message = "content must not contain NUL characters")
        String content,

        @NotNull(message = "clientEditId is required")
        @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$", message = "clientEditId must be 1-64 letters, digits or dashes")
        String clientEditId) {
}
