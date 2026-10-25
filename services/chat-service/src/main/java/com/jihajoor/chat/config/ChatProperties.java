package com.jihajoor.chat.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Tunable chat-domain limits, retention window, and Kafka topic names (Requirement 18).
 *
 * <p>Defaults match the acceptance criteria; all values are overridable via configuration so
 * operations can tune limits without a code change.
 */
@ConfigurationProperties(prefix = "jihajoor.chat")
public class ChatProperties {

    /**
     * Minimum time a message must be retained, measured from the booking's creation date and
     * independent of channel status (Requirement 18.4). Defaults to 90 days.
     */
    private Duration retention = Duration.ofDays(90);

    /**
     * Maximum time a recipient's WebSocket session may be idle before the recipient is treated as
     * offline and eligible for a push notification (Requirement 18.3).
     *
     * <p>In {@code redis} cluster mode this is the lifetime of a session's presence entry: a node
     * that stops refreshing its sessions (it crashed, or lost Redis) has them count as offline at
     * most this long afterwards, so a recipient left behind on a dead node is pushed again within
     * the window.
     */
    private Duration offlinePushWindow = Duration.ofSeconds(10);

    /** Push vendor adapter selector: {@code log} (dev/test) or a concrete Notification adapter. */
    private String pushProvider = "log";

    /**
     * How chat nodes share messages and presence:
     * <ul>
     *   <li>{@code redis} (default) — every stored message is published on a Redis channel that
     *       each node subscribes to, and presence lives in Redis, so any number of chat-service
     *       instances can run side by side;</li>
     *   <li>{@code local} — single node: messages go straight to this node's sockets and presence
     *       is in memory. For tests and a dev run without Redis.</li>
     * </ul>
     */
    private String cluster = "redis";

    @NestedConfigurationProperty
    private Redis redis = new Redis();

    @NestedConfigurationProperty
    private Topics topics = new Topics();

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public Duration getOfflinePushWindow() {
        return offlinePushWindow;
    }

    public void setOfflinePushWindow(Duration offlinePushWindow) {
        this.offlinePushWindow = offlinePushWindow;
    }

    public String getPushProvider() {
        return pushProvider;
    }

    public void setPushProvider(String pushProvider) {
        this.pushProvider = pushProvider;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Topics getTopics() {
        return topics;
    }

    public void setTopics(Topics topics) {
        this.topics = topics;
    }

    /**
     * Redis names and timings for {@code cluster=redis}. The connection itself is Spring Data
     * Redis' {@code spring.data.redis.*}, shared with the platform's other Redis users.
     */
    public static class Redis {
        /** Pub/sub channel carrying every stored message, as its {@code MessageResponse} JSON. */
        private String messagesChannel = "chat:messages";
        /**
         * Prefix of the presence keys: one sorted set per booking and user,
         * {@code <prefix><bookingId>:<userId>}, of live sessions scored by expiry time.
         */
        private String presenceKeyPrefix = "chat:presence:";
        /**
         * How often a node re-stamps the presence of its open sockets. Must be well under
         * {@code offline-push-window} (the entries' lifetime) so a live session never lapses
         * between two beats; the default allows two missed beats.
         */
        private Duration presenceHeartbeat = Duration.ofSeconds(3);

        public String getMessagesChannel() {
            return messagesChannel;
        }

        public void setMessagesChannel(String messagesChannel) {
            this.messagesChannel = messagesChannel;
        }

        public String getPresenceKeyPrefix() {
            return presenceKeyPrefix;
        }

        public void setPresenceKeyPrefix(String presenceKeyPrefix) {
            this.presenceKeyPrefix = presenceKeyPrefix;
        }

        public Duration getPresenceHeartbeat() {
            return presenceHeartbeat;
        }

        public void setPresenceHeartbeat(Duration presenceHeartbeat) {
            this.presenceHeartbeat = presenceHeartbeat;
        }
    }

    /** Kafka topic names driving the channel lifecycle (Requirement 18.1, 18.5). */
    public static class Topics {
        /** Activates a channel on transition to PROVIDER_ACCEPTED (Requirement 18.1). */
        private String providerAccepted = "ProviderAccepted";
        /** Deactivates a channel on transition to PAYMENT_COMPLETED (Requirement 18.5). */
        private String paymentCompleted = "PaymentCompleted";
        /** Deactivates a channel on transition to CANCELLED (Requirement 18.5). */
        private String bookingCancelled = "BookingCancelled";

        public String getProviderAccepted() {
            return providerAccepted;
        }

        public void setProviderAccepted(String providerAccepted) {
            this.providerAccepted = providerAccepted;
        }

        public String getPaymentCompleted() {
            return paymentCompleted;
        }

        public void setPaymentCompleted(String paymentCompleted) {
            this.paymentCompleted = paymentCompleted;
        }

        public String getBookingCancelled() {
            return bookingCancelled;
        }

        public void setBookingCancelled(String bookingCancelled) {
            this.bookingCancelled = bookingCancelled;
        }
    }
}
