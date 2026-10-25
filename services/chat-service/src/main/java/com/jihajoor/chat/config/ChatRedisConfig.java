package com.jihajoor.chat.config;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import com.jihajoor.chat.delivery.LocalMessageFanout;
import com.jihajoor.chat.delivery.MessageDeliveryPort;
import com.jihajoor.chat.delivery.RedisMessageDeliveryAdapter;
import com.jihajoor.chat.delivery.RedisMessageSubscriber;
import com.jihajoor.chat.presence.RedisPresenceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import tools.jackson.databind.ObjectMapper;

/**
 * Lets several chat-service nodes serve the same conversations ({@code jihajoor.chat.cluster=redis},
 * the default): messages cross nodes over Redis pub/sub and presence lives in Redis.
 *
 * <ul>
 *   <li>{@link MessageDeliveryPort} → {@link RedisMessageDeliveryAdapter}: a stored message is
 *       published, never delivered directly;</li>
 *   <li>a {@link RedisMessageListenerContainer} subscribes this node to the channel and feeds
 *       every message to {@link RedisMessageSubscriber}, which writes it to this node's sockets
 *       and its STOMP simple broker;</li>
 *   <li>{@link com.jihajoor.chat.presence.PresenceRegistry} → {@link RedisPresenceRegistry}, its
 *       entries re-stamped every {@code jihajoor.chat.redis.presence-heartbeat} and lapsing after
 *       {@code jihajoor.chat.offline-push-window}.</li>
 * </ul>
 *
 * <p>The connection is Spring Data Redis' auto-configured one ({@code spring.data.redis.*}: the
 * platform's {@code REDIS_HOST}/{@code REDIS_PORT}, or {@code SPRING_DATA_REDIS_URL}, which the
 * container entrypoint derives from a PaaS {@code REDIS_URL}). Lettuce connects lazily and the
 * listener container keeps retrying its subscription, so the service starts even while Redis is
 * unreachable.
 *
 * <p>{@code cluster=local} skips all of this for the single-node in-memory adapters.
 */
@Configuration
@ConditionalOnProperty(prefix = "jihajoor.chat", name = "cluster", havingValue = "redis",
        matchIfMissing = true)
public class ChatRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatRedisConfig.class);

    /** Identifies this instance in the presence members; fresh on every start. */
    private final String nodeId = UUID.randomUUID().toString();

    @Bean
    public MessageDeliveryPort redisMessageDelivery(StringRedisTemplate redis, ObjectMapper objectMapper,
                                                    ChatProperties properties) {
        return new RedisMessageDeliveryAdapter(redis, objectMapper,
                properties.getRedis().getMessagesChannel());
    }

    @Bean
    public RedisMessageSubscriber chatMessageSubscriber(LocalMessageFanout fanout,
                                                        ObjectMapper objectMapper) {
        return RedisMessageSubscriber.withVirtualThreadLanes(fanout, objectMapper);
    }

    @Bean
    public RedisMessageListenerContainer chatMessageListenerContainer(
            RedisConnectionFactory connectionFactory, RedisMessageSubscriber subscriber,
            ChatProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Called on the subscription's thread, in arrival order; the subscriber moves the socket
        // writes to its per-booking lanes itself (see RedisMessageSubscriber).
        container.setTaskExecutor(new SyncTaskExecutor());
        container.addMessageListener(subscriber,
                new ChannelTopic(properties.getRedis().getMessagesChannel()));
        return container;
    }

    @Bean
    public RedisPresenceRegistry redisPresenceRegistry(StringRedisTemplate redis,
                                                       ChatProperties properties, Clock clock) {
        Duration ttl = properties.getOfflinePushWindow();
        Duration heartbeat = properties.getRedis().getPresenceHeartbeat();
        requireHeartbeatBelowTtl(heartbeat, ttl);
        log.info("Chat presence in Redis: node {}, heartbeat {}, entries lapse after {}",
                nodeId, heartbeat, ttl);
        return new RedisPresenceRegistry(redis, properties.getRedis().getPresenceKeyPrefix(), ttl,
                clock, nodeId);
    }

    /**
     * A heartbeat no shorter than the entries' lifetime would let a live socket lapse between
     * two beats and its user be pushed while connected.
     */
    static void requireHeartbeatBelowTtl(Duration heartbeat, Duration ttl) {
        if (heartbeat == null || ttl == null || heartbeat.isNegative() || heartbeat.isZero()
                || heartbeat.compareTo(ttl) >= 0) {
            throw new IllegalStateException("jihajoor.chat.redis.presence-heartbeat (" + heartbeat
                    + ") must be positive and shorter than jihajoor.chat.offline-push-window ("
                    + ttl + ")");
        }
    }

    /** Runs the presence heartbeat; only present, like the registry, in {@code cluster=redis}. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "jihajoor.chat", name = "cluster", havingValue = "redis",
            matchIfMissing = true)
    static class PresenceHeartbeatConfig implements SchedulingConfigurer {

        private final RedisPresenceRegistry presence;
        private final ChatProperties properties;

        PresenceHeartbeatConfig(RedisPresenceRegistry presence, ChatProperties properties) {
            this.presence = presence;
            this.properties = properties;
        }

        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedDelayTask(presence::heartbeat,
                    properties.getRedis().getPresenceHeartbeat());
        }
    }
}
