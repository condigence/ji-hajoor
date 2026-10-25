package com.jihajoor.chat.config;

import com.jihajoor.chat.presence.PresenceRegistry;
import com.jihajoor.chat.service.ChatService;
import com.jihajoor.chat.service.ChatStore;
import com.jihajoor.chat.socket.ChatSocketHandler;
import com.jihajoor.chat.socket.ChatSocketHandshakeInterceptor;
import com.jihajoor.chat.socket.ChatSocketSessions;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * The plain WebSocket the customer and provider apps use for chat:
 * {@code /chat/channels/{bookingId}/ws}, one JSON message per frame (Requirement 18.2). It sits
 * under {@code /chat/**}, so the API gateway routes it and accepts the browser's
 * {@code ?access_token=} there, turning it into the bearer header the JWT filter reads.
 *
 * <p>The STOMP endpoint in {@link WebSocketConfig} stays for STOMP clients; both transports
 * receive every message.
 *
 * <p>Any origin may open the socket: it is authenticated by the bearer token, never by a cookie,
 * so a foreign page cannot ride a user's session, and the native apps connect from
 * {@code capacitor://localhost} or {@code https://localhost}.
 */
@Configuration
@EnableWebSocket
public class ChatSocketConfig implements WebSocketConfigurer {

    private final ChatService chatService;
    private final ChatStore chatStore;
    private final ChatSocketSessions sessions;
    private final PresenceRegistry presence;
    private final ObjectMapper objectMapper;

    public ChatSocketConfig(ChatService chatService, ChatStore chatStore, ChatSocketSessions sessions,
                            PresenceRegistry presence, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.chatStore = chatStore;
        this.sessions = sessions;
        this.presence = presence;
        this.objectMapper = objectMapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new ChatSocketHandler(chatService, sessions, presence, objectMapper),
                        "/chat/channels/*/ws")
                .addInterceptors(new ChatSocketHandshakeInterceptor(chatStore))
                .setAllowedOriginPatterns("*");
    }
}
