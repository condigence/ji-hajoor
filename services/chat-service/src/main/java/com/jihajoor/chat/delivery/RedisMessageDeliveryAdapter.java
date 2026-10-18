package com.jihajoor.chat.delivery;

import com.jihajoor.chat.api.dto.MessageResponse;
import com.jihajoor.chat.domain.ChatChannel;
import com.jihajoor.chat.domain.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Multi-node {@link MessageDeliveryPort} ({@code jihajoor.chat.cluster=redis}): publishes each
 * stored message, as its {@link MessageResponse} JSON, on one Redis pub/sub channel
 * ({@code jihajoor.chat.redis.messages-channel}, default {@code chat:messages}). Every node,
 * this one included, subscribes through {@link RedisMessageSubscriber} and hands the message to
 * its own sockets and STOMP subscribers (Requirement 18.2).
 *
 * <p><b>Exactly once per socket.</b> This adapter never delivers locally: the only path to a
 * socket is the subscription. Redis hands each published message once to every subscribed
 * connection, each node holds one subscription, and each socket lives on exactly one node, so
 * every socket receives the message once, and the sender's own node is not served twice.
 *
 * <p><b>After commit.</b> Inside a transaction (a send is one) the message is published once the
 * transaction commits, so no node shows a message whose insert then rolls back, and a client that
 * reloads history on any node finds it.
 *
 * <p><b>Failure policy.</b> Pub/sub is at most once. If Redis cannot take the publish, the
 * message is still stored, and the participants see it when they reload the history; the send
 * itself is not failed. The offline push is decided separately, by presence.
 */
public class RedisMessageDeliveryAdapter implements MessageDeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageDeliveryAdapter.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String channel;

    public RedisMessageDeliveryAdapter(StringRedisTemplate redis, ObjectMapper objectMapper,
                                       String channel) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.channel = channel;
    }

    @Override
    public void deliver(ChatMessage message, ChatChannel chatChannel) {
        String json = objectMapper.writeValueAsString(MessageResponse.from(message, chatChannel));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(message, json);
                }
            });
        } else {
            publish(message, json);
        }
    }

    private void publish(ChatMessage message, String json) {
        try {
            redis.convertAndSend(channel, json);
        } catch (DataAccessException e) {
            log.warn("Could not publish chat message {} of booking {} to Redis; it is stored and "
                    + "shows on the next history load: {}", message.getId(), message.getBookingId(),
                    e.getMessage());
        }
    }
}
