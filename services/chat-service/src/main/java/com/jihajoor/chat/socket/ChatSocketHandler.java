package com.jihajoor.chat.socket;

import java.io.IOException;
import java.util.UUID;

import com.jihajoor.chat.presence.PresenceRegistry;
import com.jihajoor.chat.service.ChatException;
import com.jihajoor.chat.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The apps' chat WebSocket at {@code /chat/channels/{bookingId}/ws} (Requirement 18.2).
 *
 * <p>A client frame is {@code {"body": "..."}}. It goes through {@link ChatService#sendMessage}
 * exactly like a REST send, so the participant check, the deactivation guard, phone masking,
 * persistence and offline push all apply; the stored message then comes back to every connected
 * participant, the sender included, as a {@code MessageResponse} JSON frame.
 *
 * <p>While a socket is open its user counts as online for that booking, so the other side's
 * messages arrive live instead of as a push notification (Requirement 18.3). Presence is
 * recorded per socket in the {@link PresenceRegistry}, which in a multi-node deployment is shared
 * through Redis, as is the delivery of each stored message to every node's sockets.
 *
 * <p>A closed channel ends the socket with a normal closure (1000), which the apps take as
 * "do not reconnect" (Requirement 18.5). A malformed, blank or oversized frame is dropped.
 */
public class ChatSocketHandler extends TextWebSocketHandler {

    static final String BOOKING_ATTR = "chat.bookingId";
    static final String USER_ATTR = "chat.userId";
    static final String ACTIVE_ATTR = "chat.active";

    /** Same limit as the REST send's {@code SendMessageRequest}. */
    static final int MAX_BODY_CHARS = 4000;
    /**
     * Frame size the socket accepts: 4000 characters of up to three UTF-8 bytes each, plus JSON
     * escaping. The container default (8 KB) would close the socket on a long non-Latin message.
     */
    static final int TEXT_MESSAGE_SIZE_LIMIT = 32 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);

    private final ChatService chatService;
    private final ChatSocketSessions sessions;
    private final PresenceRegistry presence;
    private final ObjectMapper objectMapper;

    /**
     * Normal closure (1000, which the apps do not retry) for a channel that is no longer active.
     * The reason tells the apps whether a frame they just sent was dropped, so they can give the
     * text back to the user instead of losing it.
     */
    static final CloseStatus CHANNEL_CLOSED = CloseStatus.NORMAL.withReason("CHANNEL_CLOSED");
    static final CloseStatus MESSAGE_NOT_DELIVERED =
            CloseStatus.NORMAL.withReason("MESSAGE_NOT_DELIVERED");

    public ChatSocketHandler(ChatService chatService, ChatSocketSessions sessions,
                             PresenceRegistry presence, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.sessions = sessions;
        this.presence = presence;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!Boolean.TRUE.equals(session.getAttributes().get(ACTIVE_ATTR))) {
            session.close(CHANNEL_CLOSED);
            return;
        }
        session.setTextMessageSizeLimit(TEXT_MESSAGE_SIZE_LIMIT);
        UUID bookingId = bookingId(session);
        sessions.add(bookingId, session);
        presence.connected(bookingId, userId(session), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
            throws IOException {
        String body = readBody(message.getPayload());
        if (body == null) {
            return;
        }
        try {
            chatService.sendMessage(bookingId(session), userId(session), body);
        } catch (ChatException e) {
            if (e.getStatus() == HttpStatus.CONFLICT) {
                // The booking ended while the socket was open; this frame was not stored.
                session.close(MESSAGE_NOT_DELIVERED);
            } else {
                log.debug("Chat frame refused on booking {}: {}", bookingId(session), e.getErrorCode());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID bookingId = bookingId(session);
        UUID userId = userId(session);
        if (bookingId == null || userId == null) {
            return;
        }
        sessions.remove(bookingId, session);
        // Per socket: a second tab or device on the same booking, on this node or another, keeps
        // the user online.
        presence.disconnected(bookingId, userId, session.getId());
    }

    /** The trimmed {@code body} of a client frame, or null when the frame is not acceptable. */
    String readBody(String payload) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (JacksonException e) {
            return null;
        }
        JsonNode body = node == null ? null : node.get("body");
        if (body == null || !body.isString()) {
            return null;
        }
        String text = body.asString().trim();
        return text.isEmpty() || text.length() > MAX_BODY_CHARS ? null : text;
    }

    private static UUID bookingId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(BOOKING_ATTR);
    }

    private static UUID userId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(USER_ATTR);
    }
}
