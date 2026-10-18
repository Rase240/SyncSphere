package com.syncsphere.collaboration;

import java.util.UUID;

/**
 * Stored in the WebSocket session attributes after a successful join: "this connection is participant X
 * in document Y". Later edit/cursor messages read it instead of trusting any identity in the payload.
 */
record JoinedDocument(UUID documentId, Participant participant) {
}
