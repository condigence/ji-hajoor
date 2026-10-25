package com.jihajoor.chat.support;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.jihajoor.chat.delivery.MessageDeliveryPort;
import com.jihajoor.chat.domain.ChatChannel;
import com.jihajoor.chat.domain.ChatMessage;
import com.jihajoor.chat.presence.PresenceRegistry;
import com.jihajoor.chat.push.NotificationPushPort;
import com.jihajoor.chat.service.ChatStore;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.types.Expiration;

/**
 * Small hand-written test doubles for the Chat Service ports (no Spring). Mockito appears only
 * inside {@link FakeRedis}, to put a {@link StringRedisTemplate} in front of its in-memory state.
 */
public final class TestDoubles {

    private TestDoubles() {
    }

    /**
     * In-memory {@link ChatStore}: one channel per bookingId, plus an ordered message list.
     *
     * <p>Models the primary key the way JPA meets it: saving a channel that reports
     * {@link ChatChannel#isNew()} is an insert, and fails if a different channel is already stored
     * for the booking. Re-saving the stored instance itself is an update, as for a managed entity.
     */
    public static class InMemoryChatStore implements ChatStore {
        private final ConcurrentHashMap<UUID, ChatChannel> channels = new ConcurrentHashMap<>();
        public final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public Optional<ChatChannel> findChannel(UUID bookingId) {
            return Optional.ofNullable(channels.get(bookingId));
        }

        @Override
        public ChatChannel saveChannel(ChatChannel channel) {
            channels.compute(channel.getBookingId(), (id, existing) -> {
                if (existing != null && existing != channel && channel.isNew()) {
                    throw new DataIntegrityViolationException(
                            "duplicate key value violates unique constraint \"chat_channel_pkey\"");
                }
                return channel;
            });
            return channel;
        }

        @Override
        public synchronized ChatMessage saveMessage(ChatMessage message) {
            messages.add(message);
            return message;
        }

        @Override
        public List<ChatMessage> findMessages(UUID bookingId) {
            return messages.stream()
                    .filter(m -> m.getBookingId().equals(bookingId))
                    .sorted((a, b) -> a.getSentAt().compareTo(b.getSentAt()))
                    .toList();
        }
    }

    /** Recording {@link MessageDeliveryPort}. */
    public static final class RecordingDeliveryPort implements MessageDeliveryPort {
        public final List<ChatMessage> delivered = new ArrayList<>();

        @Override
        public void deliver(ChatMessage message, ChatChannel channel) {
            delivered.add(message);
        }
    }

    /** {@link PresenceRegistry} whose online/offline verdict is fixed for the test. */
    public static final class FixedPresenceRegistry implements PresenceRegistry {
        private final boolean online;

        private FixedPresenceRegistry(boolean online) {
            this.online = online;
        }

        public static FixedPresenceRegistry allOnline() {
            return new FixedPresenceRegistry(true);
        }

        public static FixedPresenceRegistry allOffline() {
            return new FixedPresenceRegistry(false);
        }

        @Override
        public void connected(UUID bookingId, UUID userId, String sessionId) {
        }

        @Override
        public void disconnected(UUID bookingId, UUID userId, String sessionId) {
        }

        @Override
        public boolean isOnline(UUID bookingId, UUID userId) {
            return online;
        }
    }

    /** Recording {@link NotificationPushPort} capturing offline-recipient pushes. */
    public static final class RecordingPushPort implements NotificationPushPort {
        public final AtomicInteger pushes = new AtomicInteger();
        public volatile UUID lastRecipient;
        public volatile UUID lastBooking;

        @Override
        public void notifyUnreadMessage(UUID recipientId, UUID bookingId) {
            pushes.incrementAndGet();
            this.lastRecipient = recipientId;
            this.lastBooking = bookingId;
        }
    }

    /** A {@link Clock} that stands still until a test moves it. */
    public static final class MutableClock extends Clock {
        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /**
     * Just enough of one Redis server for the chat adapters, shared by every "node" of a test:
     * sorted sets ({@code ZADD}, {@code ZREM}, {@code ZCOUNT}, {@code ZREMRANGEBYSCORE}, with
     * Redis' inclusive score bounds), the key TTLs last set (recorded, not enforced: the presence
     * expiry under test is the members' scores), and pub/sub, which hands a published message to
     * every subscriber of the channel synchronously, in subscription order, once each.
     *
     * <p>{@link #down} makes every command fail as an unreachable server does.
     */
    public static final class FakeRedis {
        /** key → (member → score). */
        public final Map<String, Map<String, Double>> sortedSets = new ConcurrentHashMap<>();
        /** key → the TTL last set on it. */
        public final Map<String, Duration> keyTtls = new ConcurrentHashMap<>();
        /** Every payload published, per channel, in order. */
        public final Map<String, List<String>> published = new ConcurrentHashMap<>();
        public volatile boolean down;

        private final List<Map.Entry<String, MessageListener>> subscribers = new CopyOnWriteArrayList<>();
        private final StringRedisTemplate template;

        @SuppressWarnings("unchecked")
        public FakeRedis() {
            ZSetOperations<String, String> zset = Mockito.mock(ZSetOperations.class, this::zsetCommand);
            this.template = Mockito.mock(StringRedisTemplate.class,
                    invocation -> templateCommand(invocation, zset));
        }

        /** A template talking to this server; every node of a test may share it. */
        public StringRedisTemplate template() {
            return template;
        }

        /** Subscribes {@code listener} to {@code channel}, as a node's listener container does. */
        public void subscribe(String channel, MessageListener listener) {
            subscribers.add(Map.entry(channel, listener));
        }

        /** {@code PUBLISH channel payload}; returns how many subscribers received it. */
        public long publish(String channel, String payload) {
            checkUp();
            published.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(payload);
            long receivers = 0;
            for (Map.Entry<String, MessageListener> subscriber : subscribers) {
                if (subscriber.getKey().equals(channel)) {
                    subscriber.getValue().onMessage(new DefaultMessage(
                            channel.getBytes(StandardCharsets.UTF_8),
                            payload.getBytes(StandardCharsets.UTF_8)), null);
                    receivers++;
                }
            }
            return receivers;
        }

        /** The members of {@code key} with their scores, or an empty map. */
        public Map<String, Double> members(String key) {
            return sortedSets.getOrDefault(key, Map.of());
        }

        private Object templateCommand(InvocationOnMock invocation, ZSetOperations<String, String> zset)
                throws Throwable {
            Object[] args = invocation.getArguments();
            switch (invocation.getMethod().getName()) {
                case "opsForZSet":
                    return zset;
                case "expire":
                    checkUp();
                    if (args.length == 2 && args[1] instanceof Duration ttl) {
                        keyTtls.put((String) args[0], ttl);
                    } else if (args.length == 2 && args[1] instanceof Expiration expiration) {
                        keyTtls.put((String) args[0],
                                Duration.ofMillis(expiration.getExpirationTimeInMilliseconds()));
                    } else if (args.length == 3 && args[2] instanceof TimeUnit unit) {
                        keyTtls.put((String) args[0], Duration.ofMillis(unit.toMillis((Long) args[1])));
                    }
                    return Boolean.TRUE;
                case "convertAndSend":
                    return publish((String) args[0], (String) args[1]);
                default:
                    return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
        }

        private Object zsetCommand(InvocationOnMock invocation) throws Throwable {
            Object[] args = invocation.getArguments();
            String name = invocation.getMethod().getName();
            if (args.length == 0 || !(args[0] instanceof String key)) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            switch (name) {
                case "add":
                    if (args.length == 3 && args[2] instanceof Double score) {
                        checkUp();
                        return sortedSets.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                                .put((String) args[1], score) == null;
                    }
                    break;
                case "remove": {
                    checkUp();
                    Map<String, Double> set = sortedSets.get(key);
                    long removed = 0;
                    for (int i = 1; i < args.length; i++) {
                        if (set != null && set.remove(String.valueOf(args[i])) != null) {
                            removed++;
                        }
                    }
                    dropIfEmpty(key);
                    return removed;
                }
                case "count":
                    if (args.length == 3) {
                        checkUp();
                        double min = (Double) args[1];
                        double max = (Double) args[2];
                        return members(key).values().stream()
                                .filter(score -> score >= min && score <= max).count();
                    }
                    break;
                case "removeRangeByScore":
                    if (args.length == 3) {
                        checkUp();
                        double min = (Double) args[1];
                        double max = (Double) args[2];
                        Map<String, Double> set = sortedSets.get(key);
                        if (set == null) {
                            return 0L;
                        }
                        long before = set.size();
                        set.values().removeIf(score -> score >= min && score <= max);
                        long removed = before - set.size();
                        dropIfEmpty(key);
                        return removed;
                    }
                    break;
                default:
                    break;
            }
            throw new UnsupportedOperationException("FakeRedis does not model ZSetOperations." + name);
        }

        private void dropIfEmpty(String key) {
            sortedSets.computeIfPresent(key, (k, set) -> set.isEmpty() ? null : set);
        }

        private void checkUp() {
            if (down) {
                throw new RedisConnectionFailureException("Unable to connect to Redis");
            }
        }
    }
}
