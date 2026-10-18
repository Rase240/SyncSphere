package com.syncsphere.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Receives every message published on the sync channel (registered in RedisConfig) and forwards it
 * to this instance's WebSocket clients.
 *
 * <p>Runs on a Redis listener thread, not on a request thread.
 */
@Component
public class SyncEventListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SyncEventListener.class);

    private final ObjectMapper objectMapper;
    private final WebSocketBroadcaster broadcaster;

    public SyncEventListener(ObjectMapper objectMapper, WebSocketBroadcaster broadcaster) {
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        SyncEvent event;
        try {
            event = objectMapper.readValue(message.getBody(), SyncEvent.class);
        } catch (IOException e) {
            // Anyone with Redis access can publish on the channel; a bad message must not break the listener.
            log.warn("Ignoring malformed sync event on channel={} cause={}",
                    new String(message.getChannel()), e.getMessage());
            return;
        }
        if (event.eventId() == null || event.documentId() == null) {
            // Valid JSON but not a usable event (it would be routed to "/topic/documents/null").
            log.warn("Ignoring sync event without eventId/documentId type={}", event.getClass().getSimpleName());
            return;
        }
        broadcaster.deliver(event);
    }
}
