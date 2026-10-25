package com.jihajoor.chat.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.jihajoor.chat.support.TestDoubles.FakeRedis;
import com.jihajoor.chat.support.TestDoubles.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Redis presence registry shared by chat nodes (Requirement 18.3): per-socket entries that
 * every node can see, kept alive by each node's heartbeat and lapsing {@code ttl} after the last
 * one, so the users of a crashed node count as offline again. Two registries on one
 * {@link FakeRedis} play two nodes; a {@link MutableClock} moves time.
 */
class RedisPresenceRegistryTest {

    private static final String PREFIX = "chat:presence:";
    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Duration HEARTBEAT = Duration.ofSeconds(3);

    private final UUID booking = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private FakeRedis redis;
    private MutableClock clock;
    private RedisPresenceRegistry nodeA;
    private RedisPresenceRegistry nodeB;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        clock = new MutableClock(Instant.parse("2026-10-18T09:00:00Z"));
        nodeA = new RedisPresenceRegistry(redis.template(), PREFIX, TTL, clock, "node-a");
        nodeB = new RedisPresenceRegistry(redis.template(), PREFIX, TTL, clock, "node-b");
    }

    private String key() {
        return PREFIX + booking + ":" + user;
    }

    @Test
    void aSocketIsStampedWithItsExpiryUnderANodeQualifiedMember() {
        nodeA.connected(booking, user, "7");

        assertThat(nodeA.key(booking, user)).isEqualTo(key());
        assertThat(redis.members(key()))
                .containsExactlyEntriesOf(Map.of("node-a/7",
                        (double) clock.millis() + TTL.toMillis()));
        assertThat(redis.keyTtls).containsEntry(key(), TTL);
    }

    @Test
    void aSocketOnOneNodeIsOnlineForEveryNode() {
        nodeA.connected(booking, user, "1");

        assertThat(nodeA.isOnline(booking, user)).isTrue();
        assertThat(nodeB.isOnline(booking, user)).isTrue();
        assertThat(nodeB.isOnline(booking, UUID.randomUUID())).isFalse();
        assertThat(nodeB.isOnline(UUID.randomUUID(), user)).isFalse();
    }

    @Test
    void theSameSessionIdOnTwoNodesIsTwoSockets() {
        // Container session ids are only unique per node.
        nodeA.connected(booking, user, "0");
        nodeB.connected(booking, user, "0");

        nodeA.disconnected(booking, user, "0");
        assertThat(nodeA.isOnline(booking, user)).isTrue();

        nodeB.disconnected(booking, user, "0");
        assertThat(nodeA.isOnline(booking, user)).isFalse();
        assertThat(redis.sortedSets).doesNotContainKey(key());
    }

    @Test
    void closingTheLastSocketTakesTheUserOfflineAtOnce() {
        nodeA.connected(booking, user, "1");
        nodeA.connected(booking, user, "2");

        nodeA.disconnected(booking, user, "1");
        assertThat(nodeB.isOnline(booking, user)).isTrue();
        nodeA.disconnected(booking, user, "2");
        assertThat(nodeB.isOnline(booking, user)).isFalse();

        // A socket this node never recorded changes nothing.
        nodeB.connected(booking, user, "9");
        nodeA.disconnected(booking, user, "9");
        assertThat(nodeA.isOnline(booking, user)).isTrue();
    }

    @Test
    void anUnrefreshedSocketLapsesExactlyTtlAfterItsLastStamp() {
        nodeA.connected(booking, user, "1");

        clock.advance(TTL.minusMillis(1));
        assertThat(nodeB.isOnline(booking, user)).isTrue();

        // Online means expiry strictly after now.
        clock.advance(Duration.ofMillis(1));
        assertThat(nodeB.isOnline(booking, user)).isFalse();
    }

    @Test
    void theHeartbeatKeepsLiveSocketsOnlineAndACrashedNodesSocketsLapse() {
        nodeA.connected(booking, user, "live");
        nodeB.connected(booking, user, "on-b");

        // Both nodes beat for a minute, far beyond one ttl.
        for (int beat = 0; beat < 20; beat++) {
            clock.advance(HEARTBEAT);
            nodeA.heartbeat();
            nodeB.heartbeat();
        }
        assertThat(nodeA.isOnline(booking, user)).isTrue();

        // Node A crashes (stops beating, never closes); node B's socket closes cleanly.
        nodeB.disconnected(booking, user, "on-b");
        clock.advance(TTL.minus(HEARTBEAT));
        assertThat(nodeB.isOnline(booking, user)).isTrue();
        clock.advance(HEARTBEAT);
        assertThat(nodeB.isOnline(booking, user)).isFalse();
    }

    @Test
    void theHeartbeatPrunesLapsedMembersOfTheKeysItTouches() {
        nodeA.connected(booking, user, "dead");   // node A then crashes
        nodeB.connected(booking, user, "live");

        clock.advance(TTL);
        nodeB.heartbeat();

        assertThat(redis.members(key())).containsOnlyKeys("node-b/live");
        assertThat(nodeA.isOnline(booking, user)).isTrue();
    }

    @Test
    void whileRedisIsDownTheRecipientCountsAsOfflineAndTheNextHeartbeatRestoresPresence() {
        redis.down = true;
        assertThatCode(() -> nodeA.connected(booking, user, "1")).doesNotThrowAnyException();
        assertThat(nodeB.isOnline(booking, user)).isFalse();
        assertThatCode(nodeA::heartbeat).doesNotThrowAnyException();

        redis.down = false;
        assertThat(nodeB.isOnline(booking, user)).isFalse();
        nodeA.heartbeat();
        assertThat(nodeB.isOnline(booking, user)).isTrue();

        redis.down = true;
        assertThatCode(() -> nodeA.disconnected(booking, user, "1")).doesNotThrowAnyException();
        redis.down = false;
        // The member Redis kept lapses on its own, and the heartbeat no longer renews it.
        nodeA.heartbeat();
        clock.advance(TTL);
        assertThat(nodeB.isOnline(booking, user)).isFalse();
    }

    @Test
    void aCleanShutdownRemovesOnlyThisNodesSockets() {
        nodeA.connected(booking, user, "1");
        nodeB.connected(booking, user, "1");

        nodeA.close();

        assertThat(redis.members(key())).containsOnlyKeys("node-b/1");
        nodeA.heartbeat();
        assertThat(redis.members(key())).containsOnlyKeys("node-b/1");
    }
}
