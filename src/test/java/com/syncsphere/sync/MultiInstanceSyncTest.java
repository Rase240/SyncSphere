package com.syncsphere.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncsphere.SyncSphereApplication;
import com.syncsphere.collaboration.Participant;
import com.syncsphere.collaboration.PresenceService;
import com.syncsphere.document.DocumentService;
import com.syncsphere.document.DocumentSnapshot;
import com.syncsphere.support.IntegrationTestBase;
import com.syncsphere.support.StompTestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import static com.syncsphere.support.StompTestClient.next;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributed scenario: two independent application instances (two Spring contexts, two HTTP ports,
 * two in-memory STOMP brokers) sharing one PostgreSQL and one Redis. A client on instance 1 edits; a client
 * on instance 2 must receive the update. The only path between the instances is Redis Pub/Sub.
 */
class MultiInstanceSyncTest extends IntegrationTestBase {

    private static final String SECOND_INSTANCE_ID = "test-instance-2";
    private static ConfigurableApplicationContext secondInstance;
    private static int secondPort;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private PresenceService presenceService;

    @BeforeAll
    static void startSecondInstance() {
        // Passed as command-line arguments because those override application.yml (including its
        // ${DB_URL:...} placeholders). SpringApplicationBuilder.properties() would have the LOWEST precedence.
        List<String> args = new ArrayList<>();
        infrastructureProperties().forEach((key, value) -> args.add("--" + key + "=" + value));
        args.add("--server.port=0");
        args.add("--syncsphere.instance-id=" + SECOND_INSTANCE_ID);

        secondInstance = new SpringApplicationBuilder(SyncSphereApplication.class)
                .run(args.toArray(String[]::new));
        secondPort = secondInstance.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @AfterAll
    static void stopSecondInstance() {
        if (secondInstance != null) {
            secondInstance.close();
        }
    }

    @Test
    void editOnInstanceOneReachesClientOnInstanceTwo() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Distributed", "start");

        try (StompTestClient onInstance1 = StompTestClient.connect(port);
             StompTestClient onInstance2 = StompTestClient.connect(secondPort)) {
            BlockingQueue<JsonNode> instance2Updates = onInstance2.subscribe("/topic/documents/" + doc.id());
            JsonNode joined1 = onInstance1.join(doc.id(), "Alice");
            JsonNode joined2 = onInstance2.join(doc.id(), "Bob");
            assertThat(joined1.at("/you/instanceId").asText()).isEqualTo(PRIMARY_INSTANCE_ID);
            assertThat(joined2.at("/you/instanceId").asText()).isEqualTo(SECOND_INSTANCE_ID);

            onInstance1.edit(doc.id(), 0, "start + Alice's edit", "a-1");

            JsonNode update = next(instance2Updates);
            assertThat(update.get("content").asText()).isEqualTo("start + Alice's edit");
            assertThat(update.get("revision").asLong()).isEqualTo(1);
            assertThat(update.get("originInstanceId").asText()).isEqualTo(PRIMARY_INSTANCE_ID);

            // Presence is shared through Redis, so both instances see both participants.
            assertThat(presenceService.listParticipants(doc.id()))
                    .extracting(Participant::instanceId)
                    .containsExactlyInAnyOrder(PRIMARY_INSTANCE_ID, SECOND_INSTANCE_ID);
        }
    }

    @Test
    void conflictingEditsFromDifferentInstancesAreResolvedByTheDatabase() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Cross-instance race", "base");

        try (StompTestClient onInstance1 = StompTestClient.connect(port);
             StompTestClient onInstance2 = StompTestClient.connect(secondPort)) {
            BlockingQueue<JsonNode> instance1Updates = onInstance1.subscribe("/topic/documents/" + doc.id());
            BlockingQueue<JsonNode> instance2Errors = onInstance2.subscribe("/user/queue/errors");
            onInstance1.join(doc.id(), "Alice");
            onInstance2.join(doc.id(), "Bob");

            onInstance1.edit(doc.id(), 0, "Alice wins", "a-1");
            next(instance1Updates);
            onInstance2.edit(doc.id(), 0, "Bob is stale", "b-1");

            JsonNode error = next(instance2Errors);
            assertThat(error.get("code").asText()).isEqualTo("REVISION_CONFLICT");
            assertThat(error.at("/latest/content").asText()).isEqualTo("Alice wins");
        }
    }
}
