package com.jihajoor.chat.config;

import java.time.Clock;

import com.jihajoor.chat.delivery.MessageDeliveryPort;
import com.jihajoor.chat.presence.PresenceRegistry;
import com.jihajoor.chat.push.NotificationPushPort;
import com.jihajoor.chat.service.ChatService;
import com.jihajoor.chat.service.ChatStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the plain-Java {@link ChatService} and its clock so the service class stays free of Spring
 * annotations and remains unit-testable with hand-written doubles.
 */
@Configuration
public class ChatBeansConfig {

    /** System UTC clock; overridable in tests for deterministic retention/timestamps. */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ChatService chatService(ChatStore store,
                                   MessageDeliveryPort delivery,
                                   PresenceRegistry presence,
                                   NotificationPushPort push,
                                   ChatProperties properties,
                                   Clock clock) {
        return new ChatService(store, delivery, presence, push,
                properties.getRetention(), clock);
    }
}
