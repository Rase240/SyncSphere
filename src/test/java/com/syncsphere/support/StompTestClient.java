package com.syncsphere.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A small STOMP client for tests, built on Spring's own {@link WebSocketStompClient}. It talks to the
 * running server over a real WebSocket, exactly like the browser does.
 *
 * <p>Every subscription collects incoming messages (parsed as JSON) into a queue, so a test can wait for
 * the next message with a timeout instead of sleeping.
 */
public final class StompTestClient implements AutoCloseable {

    public static final long TIMEOUT_SECONDS = 5;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StompSession session;

    private StompTestClient(StompSession session) {
        this.session = session;
    }

    public static StompTestClient connect(int port) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new SimpleMessageConverter()); // we handle JSON ourselves as byte[]
        client.setDefaultHeartbeat(new long[]{0, 0});

        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return new StompTestClient(session);
    }

    public BlockingQueue<JsonNode> subscribe(String destination) {
        BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    messages.add(JSON.readTree((byte[]) payload));
                } catch (Exception e) {
                    throw new IllegalStateException("Non-JSON message on " + destination, e);
                }
            }
        });
        return messages;
    }

    public void send(String destination, Object payload) throws Exception {
        sendRaw(destination, JSON.writeValueAsString(payload));
    }

    public void sendRaw(String destination, String body) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        session.send(headers, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Subscribes to the private snapshot queue, joins the document and waits for the snapshot. */
    public JsonNode join(UUID documentId, String displayName) throws Exception {
        BlockingQueue<JsonNode> snapshots = subscribe("/user/queue/snapshot");
        send("/app/documents/" + documentId + "/join", Map.of("displayName", displayName));
        JsonNode snapshot = snapshots.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (snapshot == null) {
            throw new AssertionError("No join snapshot received for " + displayName);
        }
        return snapshot;
    }

    public void edit(UUID documentId, long baseRevision, String content, String clientEditId) throws Exception {
        send("/app/documents/" + documentId + "/edit",
                Map.of("baseRevision", baseRevision, "content", content, "clientEditId", clientEditId));
    }

    @Override
    public void close() {
        if (session.isConnected()) {
            session.disconnect();
        }
    }

    /** Waits for the next message or fails the test. */
    public static JsonNode next(BlockingQueue<JsonNode> queue) throws InterruptedException {
        JsonNode message = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (message == null) {
            throw new AssertionError("Timed out after " + TIMEOUT_SECONDS + " s waiting for a STOMP message");
        }
        return message;
    }

    /** Skips messages until one matches the given "type" (useful when presence events interleave). */
    public static JsonNode nextOfType(BlockingQueue<JsonNode> queue, String type) throws InterruptedException {
        while (true) {
            JsonNode message = next(queue);
            if (type.equals(message.path("type").asText())) {
                return message;
            }
        }
    }
}
