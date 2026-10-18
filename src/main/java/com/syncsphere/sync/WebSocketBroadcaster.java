package com.syncsphere.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * The last hop: hands a {@link SyncEvent} to the in-memory STOMP broker of THIS instance, which pushes it
 * to every WebSocket session (on this instance) subscribed to the matching topic.
 *
 * <p>Every instance runs one of these. Instance A's broker knows nothing about instance B's clients;
 * Redis Pub/Sub is what gets the event to instance B's broadcaster.
 */
@Component
public class WebSocketBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcaster.class);
    private static final int DEDUPLICATION_WINDOW = 10_000;

    private final SimpMessagingTemplate messagingTemplate;
    private final RecentEventIds recentEventIds = new RecentEventIds(DEDUPLICATION_WINDOW);

    public WebSocketBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void deliver(SyncEvent event) {
        // A duplicate can happen when a Redis publish times out after Redis actually received it:
        // the publisher falls back to local delivery AND the subscriber later receives the same event.
        if (!recentEventIds.markSeen(event.eventId())) {
            log.debug("Duplicate sync event ignored eventId={}", event.eventId());
            return;
        }
        messagingTemplate.convertAndSend(destinationFor(event), event);
    }

    /** Maps each event type to the STOMP topic browsers subscribe to. */
    static String destinationFor(SyncEvent event) {
        String documentTopic = "/topic/documents/" + event.documentId();
        return switch (event) {
            case DocumentUpdatedEvent update -> documentTopic;
            case PresenceChangedEvent presence -> documentTopic + "/presence";
            case CursorMovedEvent cursor -> documentTopic + "/cursors";
        };
    }
}
