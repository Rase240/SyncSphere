package com.syncsphere.document;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable copy of a document at one revision.
 *
 * <p>Services return this instead of the {@link Document} entity so that managed JPA objects never
 * leave the transaction (no lazy-loading surprises, no accidental writes from controllers).
 * It is also the JSON body of {@code GET /api/documents/{id}} and of the snapshot a client receives
 * when it joins a document.
 */
public record DocumentSnapshot(
        UUID id,
        String title,
        String content,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public static DocumentSnapshot from(Document document) {
        return new DocumentSnapshot(
                document.getId(),
                document.getTitle(),
                document.getContent(),
                document.getRevision(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
