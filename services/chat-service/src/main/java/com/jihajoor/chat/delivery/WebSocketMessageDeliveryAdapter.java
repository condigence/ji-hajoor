package com.jihajoor.chat.delivery;

import com.jihajoor.chat.api.dto.MessageResponse;
import com.jihajoor.chat.domain.ChatChannel;
import com.jihajoor.chat.domain.ChatMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single-node {@link MessageDeliveryPort} ({@code jihajoor.chat.cluster=local}): sends each
 * message, as a {@link MessageResponse}, straight to this node's plain WebSockets and STOMP
 * subscribers through {@link LocalMessageFanout} (Requirement 18.2).
 *
 * <p>A participant connected to another node would never see it; with several chat-service
 * instances, {@code cluster=redis} selects {@link RedisMessageDeliveryAdapter} instead.
 */
@Component
@ConditionalOnProperty(prefix = "jihajoor.chat", name = "cluster", havingValue = "local")
public class WebSocketMessageDeliveryAdapter implements MessageDeliveryPort {

    private final LocalMessageFanout fanout;

    public WebSocketMessageDeliveryAdapter(LocalMessageFanout fanout) {
        this.fanout = fanout;
    }

    @Override
    public void deliver(ChatMessage message, ChatChannel channel) {
        fanout.deliver(MessageResponse.from(message, channel));
    }
}
