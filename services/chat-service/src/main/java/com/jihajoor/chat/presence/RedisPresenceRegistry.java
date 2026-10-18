package com.jihajoor.chat.presence;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link PresenceRegistry} shared by every chat-service node through Redis
 * ({@code jihajoor.chat.cluster=redis}), so a node can tell that a recipient is connected to
 * another node and need not be pushed (Requirement 18.3).
 *
 * <p><b>Layout.</b> One sorted set per booking and user, {@code <prefix><bookingId>:<userId>}.
 * Each member is one open socket, {@code <nodeId>/<sessionId>} (session ids are only unique per
 * node), scored by the epoch millisecond at which it stops counting. A user is online while some
 * member's expiry is still ahead: {@code ZCOUNT key (now +inf > 0}.
 *
 * <p><b>Crash tolerance.</b> A clean close removes the member at once. A node that dies cannot,
 * so its members simply lapse: each node re-stamps its open sockets every heartbeat
 * ({@link #heartbeat()}), pushing their expiry another {@code ttl} ahead, and a member nobody
 * re-stamps stops counting {@code ttl} after its last beat. With the defaults (ttl = the
 * 10-second offline-push window, heartbeat every 3 s) a live socket survives two missed beats,
 * and a user stranded on a dead node is treated as offline, and pushed, within 10 s. The heartbeat
 * also prunes lapsed members and keeps the key's own TTL at {@code ttl}, so the sets of a dead
 * node's users disappear entirely.
 *
 * <p><b>Failure policy.</b> Redis errors never fail a send or a socket: an unanswerable
 * {@link #isOnline} counts as offline (a spare push beats a lost one), and a stamp that did not
 * reach Redis is repeated by the next heartbeat, because this node still lists the socket.
 *
 * <p>Expiries are stamped with each node's clock and compared with the reader's, so nodes must
 * keep their clocks in step (NTP); a skew of {@code s} shortens a member's effective lifetime by
 * {@code s}, harmless while it stays well below {@code ttl - heartbeat}.
 */
public class RedisPresenceRegistry implements PresenceRegistry, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisPresenceRegistry.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttl;
    private final Clock clock;
    private final String nodeId;

    /** This node's open sockets: member → presence key. */
    private final Map<String, String> localSessions = new ConcurrentHashMap<>();

    /**
     * @param keyPrefix prefix of the presence keys, e.g. {@code chat:presence:}
     * @param ttl       how long a stamp keeps a socket online; must exceed the heartbeat interval
     * @param nodeId    identifies this node in the members; unique per running instance
     */
    public RedisPresenceRegistry(StringRedisTemplate redis, String keyPrefix, Duration ttl,
                                 Clock clock, String nodeId) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
        this.clock = clock;
        this.nodeId = nodeId;
    }

    @Override
    public void connected(UUID bookingId, UUID userId, String sessionId) {
        String key = key(bookingId, userId);
        String member = member(sessionId);
        localSessions.put(member, key);
        try {
            stamp(key, member, clock.millis());
        } catch (DataAccessException e) {
            // Still listed locally, so the next heartbeat writes it.
            log.warn("Could not record chat presence for booking {} in Redis: {}", bookingId,
                    e.getMessage());
        }
    }

    @Override
    public void disconnected(UUID bookingId, UUID userId, String sessionId) {
        String member = member(sessionId);
        // Unlisted first, so a heartbeat running now cannot leave the member behind (see heartbeat).
        if (localSessions.remove(member) == null) {
            return;
        }
        try {
            redis.opsForZSet().remove(key(bookingId, userId), member);
        } catch (DataAccessException e) {
            // The member lapses on its own within ttl.
            log.warn("Could not clear chat presence for booking {} in Redis: {}", bookingId,
                    e.getMessage());
        }
    }

    @Override
    public boolean isOnline(UUID bookingId, UUID userId) {
        try {
            // Scores are whole milliseconds, so "expiry > now" is "expiry >= now + 1".
            Long live = redis.opsForZSet().count(key(bookingId, userId),
                    clock.millis() + 1, Double.POSITIVE_INFINITY);
            return live != null && live > 0;
        } catch (DataAccessException e) {
            log.warn("Could not read chat presence for booking {} from Redis; treating the "
                    + "recipient as offline: {}", bookingId, e.getMessage());
            return false;
        }
    }

    /**
     * Re-stamps every socket open on this node and prunes lapsed members of the keys it touches.
     * Runs every {@code jihajoor.chat.redis.presence-heartbeat}.
     *
     * <p>A socket closing while this runs may be re-stamped after its {@code ZREM}; the member is
     * therefore checked again after its stamp and removed if it has gone. {@link #disconnected}
     * unlists before it removes, so one of the two removals always follows the stamp.
     */
    public void heartbeat() {
        if (localSessions.isEmpty()) {
            return;
        }
        long now = clock.millis();
        Set<String> keys = new HashSet<>();
        try {
            for (Map.Entry<String, String> session : localSessions.entrySet()) {
                String member = session.getKey();
                String key = session.getValue();
                stamp(key, member, now);
                if (!localSessions.containsKey(member)) {
                    redis.opsForZSet().remove(key, member);
                }
                keys.add(key);
            }
            for (String key : keys) {
                redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, now);
            }
        } catch (DataAccessException e) {
            log.warn("Chat presence heartbeat could not reach Redis ({} local sockets): {}",
                    localSessions.size(), e.getMessage());
        }
    }

    /** Removes this node's sockets on shutdown, so its users do not linger online for ttl. */
    @Override
    public void close() {
        try {
            for (Map.Entry<String, String> session : localSessions.entrySet()) {
                redis.opsForZSet().remove(session.getValue(), session.getKey());
            }
        } catch (DataAccessException e) {
            log.warn("Could not clear this node's chat presence on shutdown; it lapses within {}",
                    ttl);
        }
        localSessions.clear();
    }

    private void stamp(String key, String member, long now) {
        redis.opsForZSet().add(key, member, now + ttl.toMillis());
        redis.expire(key, ttl);
    }

    String key(UUID bookingId, UUID userId) {
        return keyPrefix + bookingId + ":" + userId;
    }

    String member(String sessionId) {
        return nodeId + "/" + sessionId;
    }
}
