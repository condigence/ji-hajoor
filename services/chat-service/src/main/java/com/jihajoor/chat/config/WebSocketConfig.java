package com.jihajoor.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket configuration for real-time message delivery (Requirement 18.2).
 *
 * <p>Clients connect at {@code /ws/chat} and subscribe to their booking's destination
 * {@code /topic/chat/{bookingId}}. The simple in-memory broker serves only this node's STOMP
 * sessions; with several nodes, each receives every message over Redis pub/sub
 * ({@link ChatRedisConfig}) and sends it into its own broker, so no external STOMP relay is
 * needed. Subscription is only granted to booking participants (Property 23); the participant
 * check is enforced by the Chat Service before a subscription is accepted.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat");
    }
}
