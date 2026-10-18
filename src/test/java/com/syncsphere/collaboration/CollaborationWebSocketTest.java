package com.syncsphere.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncsphere.document.DocumentService;
import com.syncsphere.document.DocumentSnapshot;
import com.syncsphere.support.IntegrationTestBase;
import com.syncsphere.support.StompTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.syncsphere.support.StompTestClient.next;
import static com.syncsphere.support.StompTestClient.nextOfType;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests of the real-time flow: real WebSocket connections, the real STOMP broker, Redis Pub/Sub
 * and PostgreSQL. Each test uses two independent clients, like two browser tabs.
 */
class CollaborationWebSocketTest extends IntegrationTestBase {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private PresenceService presenceService;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void joiningReturnsTheCurrentSnapshotAndParticipantIdentity() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Snapshot", "existing text");

        try (StompTestClient alice = StompTestClient.connect(port)) {
            JsonNode joined = alice.join(doc.id(), "Alice");

            assertThat(joined.at("/document/content").asText()).isEqualTo("existing text");
            assertThat(joined.at("/document/revision").asLong()).isZero();
            assertThat(joined.at("/you/displayName").asText()).isEqualTo("Alice");
            assertThat(joined.at("/you/participantId").asText()).isNotBlank();
            assertThat(joined.at("/you/instanceId").asText()).isEqualTo(PRIMARY_INSTANCE_ID);
            assertThat(joined.get("participants")).hasSize(1);
        }
    }

    @Test
    void anAcceptedEditIsBroadcastToEveryClientAndPersisted() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Broadcast", "hello");

        try (StompTestClient alice = StompTestClient.connect(port);
             StompTestClient bob = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> aliceUpdates = alice.subscribe("/topic/documents/" + doc.id());
            BlockingQueue<JsonNode> bobUpdates = bob.subscribe("/topic/documents/" + doc.id());
            alice.join(doc.id(), "Alice");
            bob.join(doc.id(), "Bob");

            alice.edit(doc.id(), 0, "hello world", "edit-1");

            JsonNode seenByBob = next(bobUpdates);
            assertThat(seenByBob.get("type").asText()).isEqualTo("DOCUMENT_UPDATED");
            assertThat(seenByBob.get("revision").asLong()).isEqualTo(1);
            assertThat(seenByBob.get("content").asText()).isEqualTo("hello world");
            assertThat(seenByBob.get("editorName").asText()).isEqualTo("Alice");

            // The sender receives the same event; clientEditId lets it recognise its own acknowledgement.
            JsonNode seenByAlice = next(aliceUpdates);
            assertThat(seenByAlice.get("clientEditId").asText()).isEqualTo("edit-1");
            assertThat(seenByAlice.get("eventId").asText()).isEqualTo(seenByBob.get("eventId").asText());
        }
        assertThat(documentService.getDocument(doc.id()).content()).isEqualTo("hello world");
    }

    @Test
    void staleEditIsRejectedPrivatelyWithTheLatestSnapshot() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Conflict", "base");

        try (StompTestClient alice = StompTestClient.connect(port);
             StompTestClient bob = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> aliceErrors = alice.subscribe("/user/queue/errors");
            BlockingQueue<JsonNode> bobErrors = bob.subscribe("/user/queue/errors");
            BlockingQueue<JsonNode> aliceUpdates = alice.subscribe("/topic/documents/" + doc.id());
            alice.join(doc.id(), "Alice");
            bob.join(doc.id(), "Bob");

            bob.edit(doc.id(), 0, "Bob was first", "bob-1");
            next(aliceUpdates); // wait until Bob's edit (revision 1) is committed and broadcast
            alice.edit(doc.id(), 0, "Alice based this on revision 0", "alice-1");

            JsonNode error = next(aliceErrors);
            assertThat(error.get("code").asText()).isEqualTo("REVISION_CONFLICT");
            assertThat(error.at("/latest/revision").asLong()).isEqualTo(1);
            assertThat(error.at("/latest/content").asText()).isEqualTo("Bob was first");
            assertThat(bobErrors.poll(500, TimeUnit.MILLISECONDS)).as("only the sender is told").isNull();
            assertThat(aliceUpdates.poll(500, TimeUnit.MILLISECONDS)).as("rejected edit is not broadcast").isNull();
        }
        assertThat(documentService.getDocument(doc.id()).content()).isEqualTo("Bob was first");
    }

    @Test
    void editWithoutJoiningIsRejected() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Not joined", "safe");

        try (StompTestClient mallory = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> errors = mallory.subscribe("/user/queue/errors");

            mallory.edit(doc.id(), 0, "sneaky", "m-1");

            assertThat(next(errors).get("code").asText()).isEqualTo("NOT_JOINED");
        }
        assertThat(documentService.getDocument(doc.id()).content()).isEqualTo("safe");
    }

    @Test
    void invalidPayloadsProduceValidationErrors() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Validation", "");

        try (StompTestClient client = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> errors = client.subscribe("/user/queue/errors");

            client.send("/app/documents/" + doc.id() + "/join", Map.of("displayName", "<script>"));
            JsonNode badName = next(errors);
            assertThat(badName.get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(badName.get("details").toString()).contains("displayName may only contain");

            client.join(doc.id(), "Valid Name");
            client.send("/app/documents/" + doc.id() + "/edit", Map.of("baseRevision", -1, "content", "x"));
            assertThat(next(errors).get("code").asText()).isEqualTo("VALIDATION_FAILED");

            client.sendRaw("/app/documents/" + doc.id() + "/edit", "{this is not json");
            assertThat(next(errors).get("code").asText()).isEqualTo("INVALID_MESSAGE");
        }
    }

    @Test
    void joiningAnUnknownDocumentReturnsNotFound() throws Exception {
        try (StompTestClient client = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> errors = client.subscribe("/user/queue/errors");

            client.send("/app/documents/" + UUID.randomUUID() + "/join", Map.of("displayName", "Ghost"));

            assertThat(next(errors).get("code").asText()).isEqualTo("DOCUMENT_NOT_FOUND");
        }
    }

    /** A client must not be able to broadcast directly to a topic and bypass validation and persistence. */
    @Test
    void sendingDirectlyToABrokerTopicIsBlocked() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Guarded", "");

        try (StompTestClient victim = StompTestClient.connect(port);
             StompTestClient attacker = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> victimUpdates = victim.subscribe("/topic/documents/" + doc.id());
            victim.join(doc.id(), "Victim");

            attacker.send("/topic/documents/" + doc.id(),
                    Map.of("type", "DOCUMENT_UPDATED", "revision", 99, "content", "forged"));

            assertThat(victimUpdates.poll(1, TimeUnit.SECONDS)).as("forged frame must not be broadcast").isNull();
            // The frame was dropped, but the attacker's connection is still usable for legitimate messages.
            assertThat(attacker.join(doc.id(), "Attacker").at("/document/content").asText()).isEmpty();
            assertThat(documentService.getDocument(doc.id()).revision()).isZero();
        }
    }

    @Test
    void presenceTracksJoinsAndDisconnects() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Presence", "");

        try (StompTestClient alice = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> presence = alice.subscribe("/topic/documents/" + doc.id() + "/presence");
            alice.join(doc.id(), "Alice");
            nextOfType(presence, "PRESENCE_CHANGED"); // Alice's own JOINED event

            StompTestClient bob = StompTestClient.connect(port);
            JsonNode bobJoined = bob.join(doc.id(), "Bob");
            JsonNode joinedEvent = next(presence);
            assertThat(joinedEvent.get("change").asText()).isEqualTo("JOINED");
            assertThat(joinedEvent.at("/participant/displayName").asText()).isEqualTo("Bob");
            assertThat(bobJoined.get("participants")).hasSize(2);
            assertThat(presenceService.listParticipants(doc.id())).hasSize(2);

            bob.close(); // closes the WebSocket -> SessionDisconnectEvent on the server

            JsonNode leftEvent = next(presence);
            assertThat(leftEvent.get("change").asText()).isEqualTo("LEFT");
            assertThat(leftEvent.at("/participant/displayName").asText()).isEqualTo("Bob");
            assertThat(presenceService.listParticipants(doc.id()))
                    .extracting(Participant::displayName)
                    .containsExactly("Alice");
        }
    }

    /** Simulates a Redis restart (presence data wiped) while the user stays connected. */
    @Test
    void presenceIsRestoredByTheHeartbeatAfterRedisLosesIt() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Self-healing", "");

        try (StompTestClient alice = StompTestClient.connect(port)) {
            alice.join(doc.id(), "Alice");
            redis.delete("syncsphere:presence:" + doc.id());
            assertThat(presenceService.listParticipants(doc.id())).isEmpty();

            presenceService.refreshInstanceHeartbeat(); // normally runs every 10 s

            assertThat(presenceService.listParticipants(doc.id()))
                    .extracting(Participant::displayName)
                    .containsExactly("Alice");
        }
    }

    @Test
    void cursorMovesAreRelayedWithServerAssignedIdentity() throws Exception {
        DocumentSnapshot doc = documentService.createDocument("Cursors", "some text");

        try (StompTestClient alice = StompTestClient.connect(port);
             StompTestClient bob = StompTestClient.connect(port)) {
            BlockingQueue<JsonNode> bobCursors = bob.subscribe("/topic/documents/" + doc.id() + "/cursors");
            JsonNode aliceJoined = alice.join(doc.id(), "Alice");
            bob.join(doc.id(), "Bob");

            alice.send("/app/documents/" + doc.id() + "/cursor",
                    Map.of("revision", 0, "selectionStart", 2, "selectionEnd", 5));

            JsonNode cursor = next(bobCursors);
            assertThat(cursor.get("type").asText()).isEqualTo("CURSOR_MOVED");
            assertThat(cursor.get("displayName").asText()).isEqualTo("Alice");
            assertThat(cursor.get("participantId").asText()).isEqualTo(aliceJoined.at("/you/participantId").asText());
            assertThat(cursor.get("selectionStart").asInt()).isEqualTo(2);
            assertThat(cursor.get("selectionEnd").asInt()).isEqualTo(5);
        }
        // Cursor positions are temporary and must never become document content.
        assertThat(documentService.getDocument(doc.id()).content()).isEqualTo("some text");
        assertThat(documentService.getDocument(doc.id()).revision()).isZero();
    }
}
