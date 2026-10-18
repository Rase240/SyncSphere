package com.syncsphere.collaboration;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Gives every WebSocket connection a random, server-generated {@link Principal}.
 *
 * <p>Spring's "user destinations" ({@code /user/queue/...}, {@code @SendToUser}) need a Principal to know
 * which connection a private message belongs to. With a login system the Principal would be the
 * authenticated user; SyncSphere has no login (development-only security model), so we create an
 * anonymous one. Its name doubles as the participant ID. The client cannot choose or forge it.
 */
public class ParticipantHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        return new ParticipantPrincipal(UUID.randomUUID().toString());
    }

    record ParticipantPrincipal(String participantId) implements Principal {
        @Override
        public String getName() {
            return participantId;
        }
    }
}
