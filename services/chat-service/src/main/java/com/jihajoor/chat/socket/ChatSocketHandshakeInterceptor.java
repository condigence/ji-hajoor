package com.jihajoor.chat.socket;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

import com.jihajoor.chat.domain.ChatChannel;
import com.jihajoor.chat.service.ChatStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Admits a chat WebSocket only for a participant of the booking in the path (Requirement 18.7,
 * Property 23). The upgrade request has already passed the JWT filter, so the principal is the
 * authenticated user; anyone else is answered 401, 403 or 404 before the socket opens.
 *
 * <p>On success the booking id, user id and the channel's status at connect time are handed to
 * {@link ChatSocketHandler} as session attributes.
 */
public class ChatSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String PATH_PREFIX = "/chat/channels/";
    static final String PATH_SUFFIX = "/ws";

    private final ChatStore chatStore;

    public ChatSocketHandshakeInterceptor(ChatStore chatStore) {
        this.chatStore = chatStore;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        UUID userId = parseUuid(request.getPrincipal());
        if (userId == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        UUID bookingId = bookingIdFrom(request.getURI().getPath());
        ChatChannel channel = bookingId == null ? null : chatStore.findChannel(bookingId).orElse(null);
        if (channel == null) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        if (!channel.participants().isParticipant(userId)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(ChatSocketHandler.BOOKING_ATTR, bookingId);
        attributes.put(ChatSocketHandler.USER_ATTR, userId);
        attributes.put(ChatSocketHandler.ACTIVE_ATTR, channel.isActive());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to clean up
    }

    /** The booking id in {@code /chat/channels/{bookingId}/ws}, or null. */
    static UUID bookingIdFrom(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX) || !path.endsWith(PATH_SUFFIX)) {
            return null;
        }
        String id = path.substring(PATH_PREFIX.length(), path.length() - PATH_SUFFIX.length());
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID parseUuid(Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
