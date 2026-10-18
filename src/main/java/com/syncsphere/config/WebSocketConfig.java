package com.syncsphere.config;

import com.syncsphere.collaboration.ParticipantHandshakeHandler;
import com.syncsphere.collaboration.StompDestinationGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP-over-WebSocket setup.
 *
 * <pre>
 *  Browser ──WebSocket──▶ /ws
 *     SEND  /app/...      ──▶ @MessageMapping methods (CollaborationController)
 *     SUBSCRIBE /topic/... ◀── in-memory "simple broker" (one per instance)
 *     SUBSCRIBE /user/queue/... ◀── private replies to one connection
 * </pre>
 *
 * The simple broker lives inside each JVM, so it only reaches clients connected to that instance.
 * Cross-instance delivery is done by Redis Pub/Sub (see the sync package), not by the broker.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Content may be 50,000 characters; JSON escaping and UTF-8 can make that several times larger in bytes. */
    private static final int MAX_INBOUND_MESSAGE_BYTES = 512 * 1024;
    private static final long[] HEARTBEAT_MILLIS = {10_000, 10_000};

    private final SyncSphereProperties properties;
    private final StompDestinationGuard destinationGuard;
    private TaskScheduler heartbeatScheduler;

    public WebSocketConfig(SyncSphereProperties properties, StompDestinationGuard destinationGuard) {
        this.properties = properties;
        this.destinationGuard = destinationGuard;
    }

    /** Spring creates this scheduler as part of the broker configuration; @Lazy breaks the creation cycle. */
    @Autowired
    public void setHeartbeatScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.heartbeatScheduler = scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setHandshakeHandler(new ParticipantHandshakeHandler())
                // Browsers send an Origin header on WebSocket handshakes; reject pages from other sites.
                .setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
        // Process frames from one connection in the order they were sent (join must run before edit).
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        // Heartbeats let the server notice dead connections (e.g. a laptop lid closed) and fire
        // SessionDisconnectEvent, which removes the participant from presence.
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(HEARTBEAT_MILLIS)
                .setTaskScheduler(heartbeatScheduler);
        // Deliver messages to each connection in the order the broker received them.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(destinationGuard);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MAX_INBOUND_MESSAGE_BYTES)
                .setSendBufferSizeLimit(2 * 1024 * 1024)
                .setSendTimeLimit(15_000);
    }
}
