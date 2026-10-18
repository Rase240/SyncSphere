package com.syncsphere.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncsphere.config.SyncSphereProperties;
import com.syncsphere.document.DocumentService;
import com.syncsphere.document.DocumentSnapshot;
import com.syncsphere.support.IntegrationTestBase;
import com.syncsphere.support.StompTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.syncsphere.support.StompTestClient.next;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the Redis subscriber path really delivers events to WebSocket clients, by publishing
 * directly into Redis exactly as another application instance would.
 */
class RedisSyncTest extends IntegrationTestBase {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SyncSphereProperties properties;

    @Autowired
    private DocumentService documentService;

    @Test
    void eventPublishedByAnotherInstanceReachesLocalClientsExactlyOnce() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Remote", "");
        DocumentUpdatedEvent remoteEvent = new DocumentUpdatedEvent(UUID.randomUUID(), doc.id(),
                "some-other-instance", 1, "written elsewhere", Instant.now(), "p-1", "Remote User", "r-1");
        String json = objectMapper.writeValueAsString(remoteEvent);

        try (StompTestClient client = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> updates = client.subscribe("/topic/documents/" + doc.id());
            client.join(doc.id(), "Local User"); // join also guarantees the subscription is registered

            redis.convertAndSend(properties.redisChannel(), json);
            redis.convertAndSend(properties.redisChannel(), json); // same eventId again: must be dropped

            JsonNode received = next(updates);
            assertThat(received.get("originInstanceId").asText()).isEqualTo("some-other-instance");
            assertThat(received.get("content").asText()).isEqualTo("written elsewhere");
            assertThat(updates.poll(700, TimeUnit.MILLISECONDS)).as("duplicate delivery").isNull();
        }
    }

    @Test
    void malformedRedisMessagesAreIgnoredAndTheListenerKeepsWorking() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Resilient", "");
        DocumentUpdatedEvent validEvent = new DocumentUpdatedEvent(UUID.randomUUID(), doc.id(),
                "other", 1, "still works", Instant.now(), "p-1", "Remote", "r-2");

        try (StompTestClient client = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> updates = client.subscribe("/topic/documents/" + doc.id());
            client.join(doc.id(), "Local");

            redis.convertAndSend(properties.redisChannel(), "{not valid json");
            redis.convertAndSend(properties.redisChannel(), "{\"type\":\"UNKNOWN_TYPE\"}");
            redis.convertAndSend(properties.redisChannel(), objectMapper.writeValueAsString(validEvent));

            assertThat(next(updates).get("content").asText()).isEqualTo("still works");
        }
    }
}
