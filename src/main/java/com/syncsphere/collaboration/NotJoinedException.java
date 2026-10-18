package com.syncsphere.collaboration;

import java.util.UUID;

/**
 * Thrown when a WebSocket session sends an edit or cursor update for a document it has not joined.
 * Joining is what binds a server-assigned participant identity to the session, so it is required first.
 */
public class NotJoinedException extends RuntimeException {

    public NotJoinedException(UUID documentId) {
        super("This connection has not joined document " + documentId);
    }
}
