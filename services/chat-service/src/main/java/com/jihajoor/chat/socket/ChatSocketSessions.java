package com.jihajoor.chat.socket;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * The open chat WebSockets on this node, grouped by booking, so a stored message can be pushed to
 * every connected participant of its channel (Requirement 18.2).
 *
 * <p>Holds this node's sockets only. With several nodes, each message reaches every node over
 * Redis pub/sub ({@code RedisMessageSubscriber}) and each node broadcasts it to its own sessions
 * here, so every socket gets it once whichever node it is connected to.
 */
@Component
public class ChatSocketSessions {

    private static final Logger log = LoggerFactory.getLogger(ChatSocketSessions.class);

    /** How long one send may block before the session is dropped as too slow. */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    /** Unsent bytes a slow session may buffer before it is dropped. */
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    /** bookingId → (session id → thread-safe session). */
    private final Map<UUID, Map<String, WebSocketSession>> byBooking = new ConcurrentHashMap<>();

    public void add(UUID bookingId, WebSocketSession session) {
        // WebSocketSession.sendMessage is not thread-safe; two participants sending at once would
        // otherwise write to the same peer concurrently.
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        byBooking.compute(bookingId, (id, sessions) -> {
            Map<String, WebSocketSession> target = sessions != null ? sessions : new ConcurrentHashMap<>();
            target.put(session.getId(), safe);
            return target;
        });
    }

    public void remove(UUID bookingId, WebSocketSession session) {
        byBooking.computeIfPresent(bookingId, (id, sessions) -> {
            sessions.remove(session.getId());
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /** Sends {@code json} to every open session on the booking's channel. */
    public void broadcast(UUID bookingId, String json) {
        Map<String, WebSocketSession> sessions = byBooking.get(bookingId);
        if (sessions == null) {
            return;
        }
        TextMessage frame = new TextMessage(json);
        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(frame);
            } catch (IOException | RuntimeException e) {
                // One broken peer must not stop delivery to the other; it reconnects and reloads
                // history.
                log.warn("Could not deliver a chat message to session {} on booking {}",
                        session.getId(), bookingId);
            }
        }
    }
}
