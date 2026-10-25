package com.jihajoor.chat.presence;

import java.util.UUID;

/**
 * Tracks which users currently hold a live WebSocket connection, so the Chat Service can decide
 * whether a recipient is reachable in real time or must be reached via a push notification
 * (Requirement 18.3).
 *
 * <p>Presence is recorded per socket: a user is online on a booking while at least one of their
 * sockets on it is open, on any node, so a second tab or device keeps them online when the first
 * closes. {@code sessionId} is the WebSocket session id, unique on this node; an implementation
 * shared between nodes qualifies it with the node itself.
 *
 * <p>Modelled as a port so the presence source can evolve (in memory for a single node, Redis
 * across a horizontally scaled fleet) without touching chat business logic.
 */
public interface PresenceRegistry {

    /** Records that {@code userId} opened socket {@code sessionId} on the booking's channel. */
    void connected(UUID bookingId, UUID userId, String sessionId);

    /**
     * Records that socket {@code sessionId} closed. A no-op for a socket that was never recorded.
     */
    void disconnected(UUID bookingId, UUID userId, String sessionId);

    /**
     * @return {@code true} if {@code userId} currently has a live connection for {@code bookingId}
     *         and can therefore receive a message in real time.
     */
    boolean isOnline(UUID bookingId, UUID userId);
}
