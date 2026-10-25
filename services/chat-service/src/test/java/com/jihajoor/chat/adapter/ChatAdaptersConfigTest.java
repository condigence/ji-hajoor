package com.jihajoor.chat.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jihajoor.chat.api.dto.MessageResponse;
import com.jihajoor.chat.config.ChatProperties;
import com.jihajoor.chat.delivery.LocalMessageFanout;
import com.jihajoor.chat.delivery.WebSocketMessageDeliveryAdapter;
import com.jihajoor.chat.domain.ChatChannel;
import com.jihajoor.chat.domain.ChatMessage;
import com.jihajoor.chat.presence.InMemoryPresenceRegistry;
import com.jihajoor.chat.push.LoggingNotificationPushAdapter;
import com.jihajoor.chat.repository.ChatChannelRepository;
import com.jihajoor.chat.repository.ChatMessageRepository;
import com.jihajoor.chat.service.ChatException;
import com.jihajoor.chat.service.JpaChatStore;
import com.jihajoor.chat.socket.ChatSocketSessions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the Chat Service adapters, store, presence registry, response DTO, tunable
 * properties, and domain exception factories (Requirement 18).
 */
class ChatAdaptersConfigTest {

    private ChatMessage message(UUID bookingId) {
        return ChatMessage.create(bookingId, UUID.randomUUID(), "hi", Instant.now(),
                Instant.now().plus(Duration.ofDays(90)));
    }

    @Test
    void loggingPushAdapter_notifyUnreadMessage_doesNotThrow() {
        assertThatCode(() -> new LoggingNotificationPushAdapter()
                .notifyUnreadMessage(UUID.randomUUID(), UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void presenceRegistry_isOnlineWhileAnySocketIsOpen() {
        InMemoryPresenceRegistry registry = new InMemoryPresenceRegistry();
        UUID booking = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        assertThat(registry.isOnline(booking, user)).isFalse();
        registry.connected(booking, user, "s1");
        registry.connected(booking, user, "s2");
        assertThat(registry.isOnline(booking, user)).isTrue();
        assertThat(registry.isOnline(UUID.randomUUID(), user)).isFalse();

        registry.disconnected(booking, user, "s1");
        assertThat(registry.isOnline(booking, user)).isTrue();
        registry.disconnected(booking, user, "s2");
        assertThat(registry.isOnline(booking, user)).isFalse();
        // A socket that was never recorded is ignored.
        registry.disconnected(booking, user, "unknown");
        assertThat(registry.isOnline(booking, user)).isFalse();
    }

    @Test
    void webSocketDelivery_sendsToStompDestinationAndAppSockets() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        ChatSocketSessions sessions = mock(ChatSocketSessions.class);
        WebSocketMessageDeliveryAdapter adapter = new WebSocketMessageDeliveryAdapter(
                new LocalMessageFanout(template, sessions, JsonMapper.builder().build()));
        UUID booking = UUID.randomUUID();
        ChatMessage msg = message(booking);
        ChatChannel channel = channelWithCustomer(booking, msg.getSenderId());

        adapter.deliver(msg, channel);

        verify(template).convertAndSend(eq("/topic/chat/" + booking),
                eq((Object) MessageResponse.from(msg, channel)));
        verify(sessions).broadcast(eq(booking), contains("\"senderRole\":\"CUSTOMER\""));
    }

    @Test
    void jpaChatStore_delegatesToRepositories() {
        ChatChannelRepository channelRepo = mock(ChatChannelRepository.class);
        ChatMessageRepository messageRepo = mock(ChatMessageRepository.class);
        JpaChatStore store = new JpaChatStore(channelRepo, messageRepo);
        UUID booking = UUID.randomUUID();
        ChatChannel channel = ChatChannel.activate(booking, UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), Instant.now());
        ChatMessage msg = message(booking);

        when(channelRepo.findById(booking)).thenReturn(Optional.of(channel));
        when(channelRepo.save(channel)).thenReturn(channel);
        when(messageRepo.save(msg)).thenReturn(msg);
        when(messageRepo.findByBookingIdOrderBySentAtAsc(booking)).thenReturn(List.of(msg));

        assertThat(store.findChannel(booking)).contains(channel);
        assertThat(store.saveChannel(channel)).isEqualTo(channel);
        assertThat(store.saveMessage(msg)).isEqualTo(msg);
        assertThat(store.findMessages(booking)).containsExactly(msg);
    }

    @Test
    void messageResponse_mapsFromDomain() {
        UUID booking = UUID.randomUUID();
        ChatMessage msg = message(booking);
        MessageResponse response = MessageResponse.from(msg, channelWithCustomer(booking, msg.getSenderId()));
        assertThat(response.id()).isEqualTo(msg.getId());
        assertThat(response.channelId()).isEqualTo(booking);
        assertThat(response.bookingId()).isEqualTo(booking);
        assertThat(response.senderId()).isEqualTo(msg.getSenderId());
        assertThat(response.senderRole()).isEqualTo("CUSTOMER");
        assertThat(response.body()).isEqualTo("hi");
    }

    @Test
    void messageResponse_namesTheProviderAsSenderForTheOtherParticipant() {
        UUID booking = UUID.randomUUID();
        ChatMessage msg = message(booking);
        MessageResponse response = MessageResponse.from(msg, channelWithCustomer(booking, UUID.randomUUID()));
        assertThat(response.senderRole()).isEqualTo("PROVIDER");
    }

    private static ChatChannel channelWithCustomer(UUID booking, UUID customerId) {
        return ChatChannel.activate(booking, customerId, UUID.randomUUID(), Instant.now(), Instant.now());
    }

    @Test
    void chatProperties_exposeDefaultsAndRoundTrip() {
        ChatProperties p = new ChatProperties();
        assertThat(p.getRetention()).isEqualTo(Duration.ofDays(90));
        assertThat(p.getOfflinePushWindow()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.getPushProvider()).isEqualTo("log");
        assertThat(p.getTopics().getProviderAccepted()).isEqualTo("ProviderAccepted");
        assertThat(p.getTopics().getPaymentCompleted()).isEqualTo("PaymentCompleted");
        assertThat(p.getTopics().getBookingCancelled()).isEqualTo("BookingCancelled");
        assertThat(p.getCluster()).isEqualTo("redis");
        assertThat(p.getRedis().getMessagesChannel()).isEqualTo("chat:messages");
        assertThat(p.getRedis().getPresenceKeyPrefix()).isEqualTo("chat:presence:");
        assertThat(p.getRedis().getPresenceHeartbeat()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.getRedis().getPresenceHeartbeat()).isLessThan(p.getOfflinePushWindow());

        p.setRetention(Duration.ofDays(120));
        p.setOfflinePushWindow(Duration.ofSeconds(30));
        p.setPushProvider("notification");
        ChatProperties.Topics t = new ChatProperties.Topics();
        t.setProviderAccepted("pa");
        t.setPaymentCompleted("pc");
        t.setBookingCancelled("bc");
        p.setTopics(t);
        p.setCluster("local");
        ChatProperties.Redis r = new ChatProperties.Redis();
        r.setMessagesChannel("m");
        r.setPresenceKeyPrefix("p:");
        r.setPresenceHeartbeat(Duration.ofSeconds(1));
        p.setRedis(r);

        assertThat(p.getRetention()).isEqualTo(Duration.ofDays(120));
        assertThat(p.getOfflinePushWindow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.getPushProvider()).isEqualTo("notification");
        assertThat(p.getTopics().getProviderAccepted()).isEqualTo("pa");
        assertThat(p.getTopics().getPaymentCompleted()).isEqualTo("pc");
        assertThat(p.getTopics().getBookingCancelled()).isEqualTo("bc");
        assertThat(p.getCluster()).isEqualTo("local");
        assertThat(p.getRedis().getMessagesChannel()).isEqualTo("m");
        assertThat(p.getRedis().getPresenceKeyPrefix()).isEqualTo("p:");
        assertThat(p.getRedis().getPresenceHeartbeat()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void chatException_factoriesCarryStatusAndErrorCode() {
        assertThat(ChatException.channelNotFound("x").getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ChatException.forbidden("x").getErrorCode()).isEqualTo("CHANNEL_ACCESS_FORBIDDEN");
        assertThat(ChatException.channelDeactivated("x").getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ChatException.validation("x").getErrorCode()).isEqualTo("VALIDATION_ERROR");
        ChatException withDetails = new ChatException(HttpStatus.BAD_REQUEST, "E", "m",
                List.of("d1"));
        assertThat(withDetails.getDetails()).containsExactly("d1");
    }
}
