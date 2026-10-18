package com.syncsphere.sync;

import com.syncsphere.collaboration.Participant;
import com.syncsphere.document.DocumentSnapshot;

import java.time.Instant;
import java.util.UUID;

/**
 * An edit was accepted and committed to PostgreSQL. It carries the complete new content, not a diff,
 * so a client that missed earlier events can still jump straight to the correct state.
 *
 * @param clientEditId the ID the editing browser attached to its request; it lets that browser recognise
 *                     this event as the acknowledgement of its own edit
 */
public record DocumentUpdatedEvent(
        UUID eventId,
        UUID documentId,
        String originInstanceId,
        long revision,
        String content,
        Instant updatedAt,
        String editorParticipantId,
        String editorName,
        String clientEditId) implements SyncEvent {

    public static DocumentUpdatedEvent of(DocumentSnapshot snapshot, Participant editor, String clientEditId,
                                          String originInstanceId) {
        return new DocumentUpdatedEvent(
                UUID.randomUUID(),
                snapshot.id(),
                originInstanceId,
                snapshot.revision(),
                snapshot.content(),
                snapshot.updatedAt(),
                editor.participantId(),
                editor.displayName(),
                clientEditId);
    }
}
