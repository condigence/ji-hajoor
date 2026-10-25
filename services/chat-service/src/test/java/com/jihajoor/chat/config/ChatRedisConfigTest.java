package com.jihajoor.chat.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Start-up guard of the multi-node chat wiring: a presence heartbeat that is not shorter than the
 * entries' lifetime ({@code offline-push-window}) would let live sockets lapse between beats, so
 * connected users would be pushed (Requirement 18.3).
 */
class ChatRedisConfigTest {

    @Test
    void theDefaultHeartbeatIsAccepted() {
        ChatProperties defaults = new ChatProperties();
        assertThatCode(() -> ChatRedisConfig.requireHeartbeatBelowTtl(
                defaults.getRedis().getPresenceHeartbeat(), defaults.getOfflinePushWindow()))
                .doesNotThrowAnyException();
    }

    @Test
    void aHeartbeatNoShorterThanTheTtlIsRefused() {
        Duration ttl = Duration.ofSeconds(10);
        assertThatThrownBy(() -> ChatRedisConfig.requireHeartbeatBelowTtl(ttl, ttl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("presence-heartbeat");
        assertThatThrownBy(() -> ChatRedisConfig.requireHeartbeatBelowTtl(Duration.ofSeconds(11), ttl))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ChatRedisConfig.requireHeartbeatBelowTtl(Duration.ZERO, ttl))
                .isInstanceOf(IllegalStateException.class);
    }
}
