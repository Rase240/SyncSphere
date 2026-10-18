package com.syncsphere.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncsphere.config.SyncSphereProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks who is connected to each document, shared across all application instances.
 *
 * <h2>Data layout in Redis</h2>
 * <pre>
 * syncsphere:presence:{documentId}   HASH   participantId -> Participant as JSON
 * syncsphere:instance:{instanceId}   STRING "alive", expires after 30 s, refreshed every 10 s
 * </pre>
 *
 * <h2>Why the instance heartbeat?</h2>
 * A participant is normally removed when its WebSocket disconnects. But if an application instance
 * crashes, it never gets the chance to remove its participants. Each participant entry therefore records
 * which instance owns it, and an entry whose instance has stopped heart-beating is treated as stale and
 * deleted the next time anyone reads the list.
 *
 * <h2>Self-healing after a Redis restart</h2>
 * Redis runs without persistence (presence is temporary), so a Redis restart empties the hash while users
 * stay connected. Each instance therefore also keeps the participants connected to IT in memory and
 * re-writes them on every heartbeat, which restores the shared view within about 10 seconds.
 *
 * <p>Presence is temporary data: it is never written to PostgreSQL.
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private static final String PRESENCE_KEY_PREFIX = "syncsphere:presence:";
    private static final String INSTANCE_KEY_PREFIX = "syncsphere:instance:";
    private static final Duration INSTANCE_HEARTBEAT_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final HashOperations<String, String, String> hashes;
    private final ObjectMapper objectMapper;
    private final String instanceId;
    /** Participants whose WebSocket is connected to this instance: documentId -> (participantId -> participant). */
    private final Map<UUID, Map<String, Participant>> localParticipants = new ConcurrentHashMap<>();

    public PresenceService(StringRedisTemplate redis, ObjectMapper objectMapper, SyncSphereProperties properties) {
        this.redis = redis;
        this.hashes = redis.opsForHash();
        this.objectMapper = objectMapper;
        this.instanceId = properties.instanceId();
    }

    public void join(UUID documentId, Participant participant) {
        // compute()/computeIfPresent() run atomically per key, so a join and a leave for the same document
        // cannot interleave and lose an entry.
        localParticipants.compute(documentId, (id, participants) -> {
            Map<String, Participant> updated = participants == null ? new ConcurrentHashMap<>() : participants;
            updated.put(participant.participantId(), participant);
            return updated;
        });
        hashes.put(presenceKey(documentId), participant.participantId(), toJson(participant));
    }

    /**
     * @return {@code true} if the participant was present and has now been removed; {@code false} if it
     *         was already gone (Spring may publish more than one disconnect event for a session)
     */
    public boolean leave(UUID documentId, String participantId) {
        localParticipants.computeIfPresent(documentId, (id, participants) -> {
            participants.remove(participantId);
            return participants.isEmpty() ? null : participants; // returning null removes the entry
        });
        Long removed = hashes.delete(presenceKey(documentId), participantId);
        return removed != null && removed > 0;
    }

    /** Live participants ordered by join time; stale entries from dead instances are removed on the way. */
    public List<Participant> listParticipants(UUID documentId) {
        String key = presenceKey(documentId);
        Map<String, String> entries = hashes.entries(key);
        Map<String, Boolean> instanceAliveCache = new HashMap<>();
        List<Participant> live = new ArrayList<>();

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            Participant participant = fromJson(entry.getValue());
            boolean alive = participant != null
                    && instanceAliveCache.computeIfAbsent(participant.instanceId(), this::isInstanceAlive);
            if (alive) {
                live.add(participant);
            } else {
                hashes.delete(key, entry.getKey());
                log.info("Removed stale participant documentId={} participantId={}", documentId, entry.getKey());
            }
        }
        live.sort(Comparator.comparing(Participant::joinedAt));
        return live;
    }

    /**
     * Runs every 10 s on the scheduler. Missing three beats in a row (30 s) marks this instance as dead
     * to the others. It also re-writes this instance's participants (see "Self-healing" above).
     * A Redis outage must not crash the scheduler thread, so failures are only logged.
     */
    @Scheduled(fixedRate = 10_000)
    public void refreshInstanceHeartbeat() {
        try {
            redis.opsForValue().set(INSTANCE_KEY_PREFIX + instanceId, "alive", INSTANCE_HEARTBEAT_TTL);
            localParticipants.forEach((documentId, participants) -> participants.values().forEach(
                    participant -> hashes.put(presenceKey(documentId), participant.participantId(), toJson(participant))));
        } catch (DataAccessException e) {
            log.warn("Could not refresh presence heartbeat instanceId={} cause={}", instanceId, e.getMessage());
        }
    }

    private boolean isInstanceAlive(String candidateInstanceId) {
        if (instanceId.equals(candidateInstanceId)) {
            return true; // we are obviously running, even if our first heartbeat has not been written yet
        }
        return Boolean.TRUE.equals(redis.hasKey(INSTANCE_KEY_PREFIX + candidateInstanceId));
    }

    private static String presenceKey(UUID documentId) {
        return PRESENCE_KEY_PREFIX + documentId;
    }

    private String toJson(Participant participant) {
        try {
            return objectMapper.writeValueAsString(participant);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize participant", e);
        }
    }

    private Participant fromJson(String json) {
        try {
            return objectMapper.readValue(json, Participant.class);
        } catch (JsonProcessingException e) {
            log.warn("Ignoring unreadable presence entry cause={}", e.getMessage());
            return null;
        }
    }
}
