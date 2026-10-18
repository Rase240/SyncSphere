package com.syncsphere.document;

import java.util.UUID;

/**
 * Thrown when an edit was based on a revision that is no longer the latest one.
 * Accepting it would silently discard somebody else's newer change, so it is rejected instead.
 */
public class RevisionConflictException extends RuntimeException {

    private final UUID documentId;
    private final long baseRevision;

    public RevisionConflictException(UUID documentId, long baseRevision) {
        super("Edit for document " + documentId + " was based on stale revision " + baseRevision);
        this.documentId = documentId;
        this.baseRevision = baseRevision;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public long getBaseRevision() {
        return baseRevision;
    }
}
