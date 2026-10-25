package com.jihajoor.chat.delivery;

import com.jihajoor.chat.api.dto.MessageResponse;
import com.jihajoor.chat.socket.ChatSocketSessions;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Hands one stored message to the real-time clients connected to <em>this</em> node, on both
 * transports (Requirement 18.2):
 * <ul>
 *   <li>the apps' plain WebSockets on {@code /chat/channels/{bookingId}/ws}, via
 *       {@link ChatSocketSessions};</li>
 *   <li>the STOMP destination {@code /topic/chat/{bookingId}}, which this node's simple broker
 *       fans out to its subscribed STOMP sessions.</li>
 * </ul>
 *
 * <p>Both registries hold only local sessions, so with several nodes each must run this once per
 * message: {@link WebSocketMessageDeliveryAdapter} calls it directly on a single node, and
 * {@link RedisMessageSubscriber} calls it on every node for each message read off the Redis
 * channel.
 *
 * <p>Only the two booking participants ever hold either kind of session, because the handshake
 * and the STOMP subscription are both gated by the participant-only access rule (Property 23).
 * The message body has already been phone-masked upstream (Requirement 18.8).
 */
@Component
public class LocalMessageFanout {

    static final String DESTINATION_PREFIX = "/topic/chat/";

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatSocketSessions sessions;
    private final ObjectMapper objectMapper;

    public LocalMessageFanout(SimpMessagingTemplate messagingTemplate, ChatSocketSessions sessions,
                              ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    /** Delivers {@code view} to this node's sockets and STOMP subscribers. */
    public void deliver(MessageResponse view) {
        deliver(view, objectMapper.writeValueAsString(view));
    }

    /**
     * Delivers a message whose JSON form is already at hand (as read off Redis), so the plain
     * sockets receive exactly the publisher's frame. {@code json} must be {@code view}
     * serialised.
     */
    public void deliver(MessageResponse view, String json) {
        sessions.broadcast(view.bookingId(), json);
        messagingTemplate.convertAndSend(DESTINATION_PREFIX + view.bookingId(), view);
    }
}
