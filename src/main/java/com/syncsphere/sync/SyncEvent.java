package com.syncsphere.sync;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * A synchronization event that one application instance publishes to Redis and every instance
 * (including the publisher) delivers to its own WebSocket clients.
 *
 * <p>The interface is {@code sealed}: these three records are the only possible events, so a
 * {@code switch} over a SyncEvent is checked by the compiler for completeness (see WebSocketBroadcaster).
 *
 * <p>{@code @JsonTypeInfo} adds a {@code "type"} property to the JSON, e.g.
 * {@code {"type":"DOCUMENT_UPDATED","eventId":"...",...}}, which lets the receiving instance
 * deserialize the JSON back into the correct record class. The browser sees the same JSON.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DocumentUpdatedEvent.class, name = "DOCUMENT_UPDATED"),
        @JsonSubTypes.Type(value = PresenceChangedEvent.class, name = "PRESENCE_CHANGED"),
        @JsonSubTypes.Type(value = CursorMovedEvent.class, name = "CURSOR_MOVED")
})
public sealed interface SyncEvent permits DocumentUpdatedEvent, PresenceChangedEvent, CursorMovedEvent {

    /** Unique per event; used to drop duplicates. */
    UUID eventId();

    UUID documentId();

    /** Which application instance produced the event (for logs and the demo UI). */
    String originInstanceId();
}
