package com.syncsphere.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Allow-list for STOMP destinations, applied to every frame a browser sends (inbound channel).
 *
 * <p>Why this matters: with Spring's simple broker, a client that SENDs directly to {@code /topic/...}
 * would have its message broadcast to every subscriber, bypassing validation, the revision check and
 * persistence entirely. So clients may only SEND to the three {@code /app} endpoints, and may only
 * SUBSCRIBE to the document topics and their own private queues.
 *
 * <p>A disallowed frame is dropped by returning {@code null} from {@link #preSend}, and a warning is logged.
 * The client gets no reply. (We tried throwing an exception instead, expecting Spring to send a STOMP ERROR
 * frame. With {@code setPreserveReceiveOrder(true)}, Spring's ordered inbound channel catches the exception,
 * logs it as an ERROR with a stack trace, and drops the frame anyway, so no ERROR frame is sent. Returning
 * null has the same effect without the noisy log.)
 */
@Component
public class StompDestinationGuard implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompDestinationGuard.class);

    private static final String UUID_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final Pattern ALLOWED_SEND =
            Pattern.compile("^/app/documents/" + UUID_REGEX + "/(join|edit|cursor)$");

    private static final Pattern ALLOWED_SUBSCRIBE = Pattern.compile(
            "^(/topic/documents/" + UUID_REGEX + "(/presence|/cursors)?|/user/queue/(snapshot|errors))$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeats and internal messages
        }
        StompCommand command = accessor.getCommand();
        String destination = accessor.getDestination();

        boolean allowed = switch (command) {
            case SEND -> matches(ALLOWED_SEND, destination);
            case SUBSCRIBE -> matches(ALLOWED_SUBSCRIBE, destination);
            default -> true; // CONNECT, DISCONNECT, UNSUBSCRIBE, ... carry no destination we need to police
        };
        if (!allowed) {
            log.warn("Dropped STOMP {} to disallowed destination={} sessionId={}",
                    command, destination, accessor.getSessionId());
            return null; // null = do not pass the frame on to the broker or the controllers
        }
        return message;
    }

    static boolean isAllowedSend(String destination) {
        return matches(ALLOWED_SEND, destination);
    }

    static boolean isAllowedSubscribe(String destination) {
        return matches(ALLOWED_SUBSCRIBE, destination);
    }

    private static boolean matches(Pattern pattern, String destination) {
        return destination != null && pattern.matcher(destination).matches();
    }
}
