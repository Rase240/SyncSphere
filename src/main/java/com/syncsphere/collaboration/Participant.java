package com.syncsphere.collaboration;

import java.time.Instant;

/**
 * One connected browser session in a document.
 *
 * <p>Identity note: {@code participantId} is a random ID the SERVER assigns per WebSocket connection,
 * and {@code color} is derived from it on the server. {@code displayName} is whatever the user typed;
 * it is validated for length/characters but is NOT an authenticated identity.
 *
 * @param instanceId the application instance holding this participant's WebSocket connection
 */
public record Participant(
        String participantId,
        String displayName,
        String color,
        String instanceId,
        Instant joinedAt) {
}
