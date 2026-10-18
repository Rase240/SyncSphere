package com.syncsphere.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncsphere.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Calls the REST API over real HTTP and checks status codes and JSON bodies. */
class DocumentRestApiTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate http;

    @Test
    void createReturns201WithLocationAndBody() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/documents",
                Map.of("title", "Design review", "content", "Draft"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body.get("title").asText()).isEqualTo("Design review");
        assertThat(body.get("content").asText()).isEqualTo("Draft");
        assertThat(body.get("revision").asLong()).isZero();
        assertThat(response.getHeaders().getLocation()).hasPath("/api/documents/" + body.get("id").asText());
    }

    @Test
    void createdDocumentCanBeFetchedAndAppearsInTheList() {
        JsonNode created = http.postForObject("/api/documents", Map.of("title", "Listed"), JsonNode.class);
        String id = created.get("id").asText();

        JsonNode fetched = http.getForObject("/api/documents/" + id, JsonNode.class);
        JsonNode list = http.getForObject("/api/documents", JsonNode.class);

        assertThat(fetched.get("content").asText()).isEmpty(); // content is optional on create
        assertThat(list.findValuesAsText("id")).contains(id);
        assertThat(list.get(0).has("content")).as("list rows omit content").isFalse();
    }

    @Test
    void blankTitleIsRejectedWithFieldErrors() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/documents", Map.of("title", "  "), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").toString()).contains("title must not be blank");
    }

    @Test
    void oversizedTitleIsRejected() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/documents",
                Map.of("title", "x".repeat(Document.MAX_TITLE_LENGTH + 1)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** PostgreSQL cannot store U+0000; without validation this surfaced as a misleading 503. */
    @Test
    void contentWithNulCharacterIsRejectedAsInvalidInput() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/documents",
                Map.of("title", "NUL", "content", "a\u0000b"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").toString()).contains("must not contain NUL characters");
    }

    @Test
    void malformedJsonIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<JsonNode> response = http.postForEntity("/api/documents",
                new HttpEntity<>("{not json", headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("detail").asText()).contains("not valid JSON");
    }

    @Test
    void unknownDocumentReturns404() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/documents/" + UUID.randomUUID(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("status").asInt()).isEqualTo(404);
    }

    @Test
    void malformedDocumentIdReturns400() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/documents/not-a-uuid", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("detail").asText()).contains("expected a UUID");
    }

    @Test
    void participantsOfAnUntouchedDocumentIsAnEmptyList() {
        JsonNode created = http.postForObject("/api/documents", Map.of("title", "Quiet"), JsonNode.class);

        JsonNode participants = http.getForObject("/api/documents/" + created.get("id").asText() + "/participants",
                JsonNode.class);

        assertThat(participants.isArray()).isTrue();
        assertThat(participants).isEmpty();
    }

    @Test
    void instanceEndpointReportsConfiguredInstanceId() {
        JsonNode instance = http.getForObject("/api/instance", JsonNode.class);

        assertThat(instance.get("instanceId").asText()).isEqualTo(PRIMARY_INSTANCE_ID);
    }

    @Test
    void readinessHealthIncludesDatabaseAndRedis() {
        JsonNode health = http.getForObject("/actuator/health/readiness", JsonNode.class);

        assertThat(health.get("status").asText()).isEqualTo("UP");
        assertThat(health.get("components").has("db")).isTrue();
        assertThat(health.get("components").has("redis")).isTrue();
    }
}
