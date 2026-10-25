package com.jihajoor.chat.presence;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single-node {@link PresenceRegistry}: the open sockets of each {@code (bookingId, userId)}, in
 * memory.
 *
 * <p>Selected by {@code jihajoor.chat.cluster=local} (tests, a dev run without Redis). It only
 * sees this node's sockets, so with several chat-service instances it would call a user offline
 * who is connected to another node; {@code cluster=redis} uses {@link RedisPresenceRegistry}.
 */
@Component
@ConditionalOnProperty(prefix = "jihajoor.chat", name = "cluster", havingValue = "local")
public class InMemoryPresenceRegistry implements PresenceRegistry {

    /** "bookingId|userId" → that user's open session ids on the booking. */
    private final Map<String, Set<String>> sessionsByPair = new ConcurrentHashMap<>();

    @Override
    public void connected(UUID bookingId, UUID userId, String sessionId) {
        sessionsByPair.computeIfAbsent(key(bookingId, userId), k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);
    }

    @Override
    public void disconnected(UUID bookingId, UUID userId, String sessionId) {
        sessionsByPair.computeIfPresent(key(bookingId, userId), (k, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    @Override
    public boolean isOnline(UUID bookingId, UUID userId) {
        Set<String> sessions = sessionsByPair.get(key(bookingId, userId));
        return sessions != null && !sessions.isEmpty();
    }

    private static String key(UUID bookingId, UUID userId) {
        return bookingId + "|" + userId;
    }
}
