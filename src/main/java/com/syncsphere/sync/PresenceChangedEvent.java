package com.syncsphere.sync;

import com.syncsphere.collaboration.Participant;

import java.util.UUID;

/**
 * Somebody joined or left a document. This is a delta (one participant), not the full list:
 * clients get the full list in their join snapshot and periodically from
 * {@code GET /api/documents/{id}/participants}, which repairs any missed or reordered event.
 */
public record PresenceChangedEvent(
        UUID eventId,
        UUID documentId,
        String originInstanceId,
        Change change,
        Participant participant) implements SyncEvent {

    public enum Change { JOINED, LEFT }

    public static PresenceChangedEvent of(UUID documentId, Change change, Participant participant,
                                          String originInstanceId) {
        return new PresenceChangedEvent(UUID.randomUUID(), documentId, originInstanceId, change, participant);
    }
}
