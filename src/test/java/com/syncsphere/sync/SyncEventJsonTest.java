package com.syncsphere.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.syncsphere.collaboration.Participant;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Events travel between instances as JSON. These tests pin the wire format: the "type" discriminator must be
 * present, and every event must survive a serialize/deserialize round trip unchanged.
 */
class SyncEventJsonTest {

    // Same defaults as the ObjectMapper Spring Boot configures (JavaTimeModule, ISO-8601 dates).
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final UUID documentId = UUID.randomUUID();
    private final Participant alice = new Participant("p-1", "Alice", "#e6194b", "app-1",
            Instant.parse("2026-01-01T10:00:00Z"));

    @Test
    void documentUpdatedEventRoundTripsWithTypeDiscriminator() throws Exception {
        DocumentUpdatedEvent event = new DocumentUpdatedEvent(UUID.randomUUID(), documentId, "app-1", 7,
                "line one\nline \"two\"", Instant.parse("2026-01-01T10:00:05Z"), "p-1", "Alice", "edit-42");

        String json = objectMapper.writeValueAsString(event);
        JsonNode tree = objectMapper.readTree(json);
        SyncEvent decoded = objectMapper.readValue(json, SyncEvent.class);

        assertThat(tree.get("type").asText()).isEqualTo("DOCUMENT_UPDATED");
        assertThat(tree.get("updatedAt").asText()).isEqualTo("2026-01-01T10:00:05Z");
        assertThat(decoded).isEqualTo(event);
    }

    @Test
    void presenceAndCursorEventsRoundTrip() throws Exception {
        SyncEvent presence = PresenceChangedEvent.of(documentId, PresenceChangedEvent.Change.LEFT, alice, "app-2");
        SyncEvent cursor = CursorMovedEvent.of(documentId, alice, 3, 10, 15, "app-1");

        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(presence), SyncEvent.class))
                .isEqualTo(presence);
        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(cursor), SyncEvent.class))
                .isEqualTo(cursor);
    }

    @Test
    void eachEventTypeIsRoutedToItsOwnTopic() {
        SyncEvent update = new DocumentUpdatedEvent(UUID.randomUUID(), documentId, "app-1", 1, "", Instant.now(),
                "p-1", "Alice", "e-1");
        SyncEvent presence = PresenceChangedEvent.of(documentId, PresenceChangedEvent.Change.JOINED, alice, "app-1");
        SyncEvent cursor = CursorMovedEvent.of(documentId, alice, 1, 0, 0, "app-1");

        assertThat(WebSocketBroadcaster.destinationFor(update)).isEqualTo("/topic/documents/" + documentId);
        assertThat(WebSocketBroadcaster.destinationFor(presence)).isEqualTo("/topic/documents/" + documentId + "/presence");
        assertThat(WebSocketBroadcaster.destinationFor(cursor)).isEqualTo("/topic/documents/" + documentId + "/cursors");
    }

    @Test
    void factoryMethodsAssignUniqueEventIds() {
        SyncEvent first = CursorMovedEvent.of(documentId, alice, 1, 0, 0, "app-1");
        SyncEvent second = CursorMovedEvent.of(documentId, alice, 1, 0, 0, "app-1");

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }
}
