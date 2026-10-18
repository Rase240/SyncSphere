package com.syncsphere.collaboration;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StompDestinationGuardTest {

    private static final String DOC = UUID.randomUUID().toString();
    private final StompDestinationGuard guard = new StompDestinationGuard();

    @Test
    void clientsMaySendOnlyToTheThreeApplicationEndpoints() {
        assertThat(StompDestinationGuard.isAllowedSend("/app/documents/" + DOC + "/join")).isTrue();
        assertThat(StompDestinationGuard.isAllowedSend("/app/documents/" + DOC + "/edit")).isTrue();
        assertThat(StompDestinationGuard.isAllowedSend("/app/documents/" + DOC + "/cursor")).isTrue();

        assertThat(StompDestinationGuard.isAllowedSend("/topic/documents/" + DOC)).isFalse();
        assertThat(StompDestinationGuard.isAllowedSend("/app/documents/" + DOC + "/delete")).isFalse();
        assertThat(StompDestinationGuard.isAllowedSend("/app/documents/not-a-uuid/edit")).isFalse();
        assertThat(StompDestinationGuard.isAllowedSend("/app/documents/" + DOC + "/edit/extra")).isFalse();
        assertThat(StompDestinationGuard.isAllowedSend(null)).isFalse();
    }

    @Test
    void clientsMaySubscribeOnlyToDocumentTopicsAndTheirPrivateQueues() {
        assertThat(StompDestinationGuard.isAllowedSubscribe("/topic/documents/" + DOC)).isTrue();
        assertThat(StompDestinationGuard.isAllowedSubscribe("/topic/documents/" + DOC + "/presence")).isTrue();
        assertThat(StompDestinationGuard.isAllowedSubscribe("/topic/documents/" + DOC + "/cursors")).isTrue();
        assertThat(StompDestinationGuard.isAllowedSubscribe("/user/queue/errors")).isTrue();
        assertThat(StompDestinationGuard.isAllowedSubscribe("/user/queue/snapshot")).isTrue();

        assertThat(StompDestinationGuard.isAllowedSubscribe("/topic/#")).isFalse();
        assertThat(StompDestinationGuard.isAllowedSubscribe("/topic/documents/*")).isFalse();
        // Subscribing to another user's translated queue directly must not be possible.
        assertThat(StompDestinationGuard.isAllowedSubscribe("/queue/errors-user123")).isFalse();
        assertThat(StompDestinationGuard.isAllowedSubscribe("/app/documents/" + DOC + "/join")).isFalse();
    }

    @Test
    void disallowedFramesAreDropped() {
        Message<byte[]> forgedSend = stompFrame(StompCommand.SEND, "/topic/documents/" + DOC);
        Message<byte[]> sneakySubscribe = stompFrame(StompCommand.SUBSCRIBE, "/topic/documents/*");

        assertThat(guard.preSend(forgedSend, null)).isNull();
        assertThat(guard.preSend(sneakySubscribe, null)).isNull();
    }

    @Test
    void allowedFrameAndNonDestinationFramesPassThroughUnchanged() {
        Message<byte[]> edit = stompFrame(StompCommand.SEND, "/app/documents/" + DOC + "/edit");
        Message<byte[]> connect = stompFrame(StompCommand.CONNECT, null);

        assertThat(guard.preSend(edit, null)).isSameAs(edit);
        assertThat(guard.preSend(connect, null)).isSameAs(connect);
    }

    private static Message<byte[]> stompFrame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
