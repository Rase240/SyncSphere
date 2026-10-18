package com.syncsphere.document;

import java.util.UUID;

/** Thrown when a document ID is well-formed but no such row exists. Mapped to HTTP 404 / STOMP error. */
public class DocumentNotFoundException extends RuntimeException {

    private final UUID documentId;

    public DocumentNotFoundException(UUID documentId) {
        super("Document " + documentId + " does not exist");
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
