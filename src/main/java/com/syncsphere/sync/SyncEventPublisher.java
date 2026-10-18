package com.syncsphere.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncsphere.config.SyncSphereProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends a {@link SyncEvent} to every application instance via Redis Pub/Sub.
 *
 * <p>Normal path: serialize to JSON, {@code PUBLISH} on the shared channel, and do nothing else. This
 * instance also subscribes to that channel (see SyncEventListener), so its own clients receive the event
 * the same way as clients of other instances. One delivery path = no special cases and no duplicates.
 *
 * <p>Fallback path: if Redis is unreachable, deliver to this instance's clients directly so users on the
 * same instance can keep working. Users on other instances miss the event; their clients recover by
 * reloading the authoritative snapshot (revision check / periodic resync).
 */
@Component
public class SyncEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SyncEventPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WebSocketBroadcaster broadcaster;
    private final String channel;

    public SyncEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              WebSocketBroadcaster broadcaster, SyncSphereProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.broadcaster = broadcaster;
        this.channel = properties.redisChannel();
    }

    public void publish(SyncEvent event) {
        String json = toJson(event);
        try {
            redisTemplate.convertAndSend(channel, json);
            log.debug("Published sync event type={} eventId={} documentId={}",
                    event.getClass().getSimpleName(), event.eventId(), event.documentId());
        } catch (DataAccessException e) {
            log.warn("Redis publish failed, delivering to local clients only eventId={} documentId={} cause={}",
                    event.eventId(), event.documentId(), e.getMessage());
            broadcaster.deliver(event);
        }
    }

    private String toJson(SyncEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Our own records always serialize; failing here is a programming error, not a runtime condition.
            throw new IllegalStateException("Could not serialize sync event " + event.eventId(), e);
        }
    }
}
