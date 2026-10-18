package com.syncsphere.sync;

import com.syncsphere.collaboration.Participant;

import java.util.UUID;

/**
 * A participant moved their caret or changed their selection. Never persisted: it is only useful
 * for the next few seconds, and losing one is harmless because the next cursor move replaces it.
 *
 * <p>The offsets refer to the document text at {@code revision}; a client looking at a different
 * revision may show the position slightly off, which is acceptable for a presence hint.
 */
public record CursorMovedEvent(
        UUID eventId,
        UUID documentId,
        String originInstanceId,
        String participantId,
        String displayName,
        String color,
        long revision,
        int selectionStart,
        int selectionEnd) implements SyncEvent {

    public static CursorMovedEvent of(UUID documentId, Participant participant, long revision,
                                      int selectionStart, int selectionEnd, String originInstanceId) {
        return new CursorMovedEvent(UUID.randomUUID(), documentId, originInstanceId,
                participant.participantId(), participant.displayName(), participant.color(),
                revision, selectionStart, selectionEnd);
    }
}
