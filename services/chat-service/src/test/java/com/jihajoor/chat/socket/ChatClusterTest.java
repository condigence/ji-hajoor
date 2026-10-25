package com.jihajoor.chat.socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.jihajoor.chat.api.dto.MessageResponse;
import com.jihajoor.chat.delivery.LocalMessageFanout;
import com.jihajoor.chat.delivery.RedisMessageDeliveryAdapter;
import com.jihajoor.chat.delivery.RedisMessageSubscriber;
import com.jihajoor.chat.domain.ChatChannel;
import com.jihajoor.chat.domain.ChatMessage;
import com.jihajoor.chat.presence.RedisPresenceRegistry;
import com.jihajoor.chat.service.ChatService;
import com.jihajoor.chat.support.TestDoubles.FakeRedis;
import com.jihajoor.chat.support.TestDoubles.InMemoryChatStore;
import com.jihajoor.chat.support.TestDoubles.MutableClock;
import com.jihajoor.chat.support.TestDoubles.RecordingPushPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chat across two chat-service nodes sharing one Redis (Requirement 18.2, 18.3): every stored
 * message reaches every socket and STOMP broker on every node exactly once, whichever node it was
 * sent on, and a recipient connected to the other node is not pushed until that node's presence
 * lapses. Each node is the production wiring ({@link RedisMessageDeliveryAdapter},
 * {@link RedisMessageSubscriber}, {@link RedisPresenceRegistry}) over a {@link FakeRedis}.
 */
class ChatClusterTest {

    private static final String CHANNEL = "chat:messages";
    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Instant CREATED = Instant.parse("2026-10-01T09:00:00Z");

    private final UUID booking = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final JsonMapper json = JsonMapper.builder().build();

    private FakeRedis redis;
    private MutableClock clock;
    private InMemoryChatStore store;
    private RecordingPushPort push;
    private Node nodeA;
    private Node nodeB;

    /** One chat-service instance: its own sockets, STOMP broker and presence view. */
    private final class Node {
        final ChatSocketSessions sessions = new ChatSocketSessions();
        final SimpMessagingTemplate stomp = mock(SimpMessagingTemplate.class);
        final RedisPresenceRegistry presence;
        final ChatSocketHandler handler;

        Node(String nodeId) {
            presence = new RedisPresenceRegistry(redis.template(), "chat:presence:", TTL, clock, nodeId);
            redis.subscribe(CHANNEL, new RedisMessageSubscriber(
                    new LocalMessageFanout(stomp, sessions, json), json, List.<Executor>of(Runnable::run)));
            ChatService chat = new ChatService(store,
                    new RedisMessageDeliveryAdapter(redis.template(), json, CHANNEL),
                    presence, push, Duration.ofDays(90), clock);
            handler = new ChatSocketHandler(chat, sessions, presence, json);
        }

        WebSocketSession open(UUID user) throws Exception {
            WebSocketSession session = mock(WebSocketSession.class);
            Map<String, Object> attributes = new HashMap<>();
            attributes.put(ChatSocketHandler.BOOKING_ATTR, booking);
            attributes.put(ChatSocketHandler.USER_ATTR, user);
            attributes.put(ChatSocketHandler.ACTIVE_ATTR, true);
            when(session.getAttributes()).thenReturn(attributes);
            when(session.getId()).thenReturn(UUID.randomUUID().toString());
            when(session.isOpen()).thenReturn(true);
            handler.afterConnectionEstablished(session);
            return session;
        }

        void send(WebSocketSession from, String body) throws Exception {
            handler.handleMessage(from, new TextMessage("{\"body\":\"" + body + "\"}"));
        }

        /** What this node's STOMP broker was asked to send to the booking's destination. */
        List<Object> stompPayloads() {
            ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
            verify(stomp, atLeast(0)).convertAndSend(eq("/topic/chat/" + booking), payloads.capture());
            return payloads.getAllValues();
        }
    }

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        clock = new MutableClock(Instant.parse("2026-10-18T09:00:00Z"));
        store = new InMemoryChatStore();
        store.saveChannel(ChatChannel.activate(booking, customer, provider, CREATED, CREATED));
        push = new RecordingPushPort();
        nodeA = new Node("node-a");
        nodeB = new Node("node-b");
    }

    private static List<String> framesSentTo(WebSocketSession session) throws Exception {
        ArgumentCaptor<WebSocketMessage> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, atLeast(0)).sendMessage(captor.capture());
        List<String> frames = new ArrayList<>();
        for (WebSocketMessage<?> m : captor.getAllValues()) {
            frames.add(((TextMessage) m).getPayload());
        }
        return frames;
    }

    private List<String> published() {
        return redis.published.getOrDefault(CHANNEL, List.of());
    }

    @Test
    void aMessageReachesEverySocketAndBrokerOnEveryNodeExactlyOnce() throws Exception {
        WebSocketSession customerTab = nodeA.open(customer);
        WebSocketSession customerSecondTab = nodeA.open(customer);
        WebSocketSession providerSocket = nodeB.open(provider);

        nodeA.send(customerTab, "on my way, call 9876543210");

        assertThat(store.messages).hasSize(1);
        // Published once, and the origin node did not also deliver it directly.
        assertThat(published()).singleElement().satisfies(frame -> {
            assertThat(frame).contains("\"senderRole\":\"CUSTOMER\"");
            assertThat(frame).doesNotContain("9876543210");
        });
        String frame = published().get(0);
        for (WebSocketSession socket : List.of(customerTab, customerSecondTab, providerSocket)) {
            assertThat(framesSentTo(socket)).containsExactly(frame);
        }
        MessageResponse view = MessageResponse.from(store.messages.get(0),
                store.findChannel(booking).orElseThrow());
        assertThat(nodeA.stompPayloads()).containsExactly(view);
        assertThat(nodeB.stompPayloads()).containsExactly(view);
        // The provider is connected, to the other node (Requirement 18.3).
        assertThat(push.pushes.get()).isZero();
    }

    @Test
    void aMessagePublishedByAnotherNodeReachesThisNodesSocketsAndBroker() throws Exception {
        WebSocketSession customerSocket = nodeA.open(customer);
        WebSocketSession providerSocket = nodeA.open(provider);
        MessageResponse view = new MessageResponse(UUID.randomUUID(), booking, booking, provider,
                MessageResponse.PROVIDER, "reached the gate", Instant.parse("2026-10-18T09:01:00Z"));
        String frame = json.writeValueAsString(view);

        // As published by a third node this test does not run.
        redis.publish(CHANNEL, frame);

        assertThat(framesSentTo(customerSocket)).containsExactly(frame);
        assertThat(framesSentTo(providerSocket)).containsExactly(frame);
        assertThat(nodeA.stompPayloads()).containsExactly(view);
    }

    @Test
    void anotherBookingsMessageReachesNoSocketOfThisBooking() throws Exception {
        WebSocketSession customerSocket = nodeA.open(customer);
        UUID otherBooking = UUID.randomUUID();
        MessageResponse view = new MessageResponse(UUID.randomUUID(), otherBooking, otherBooking,
                UUID.randomUUID(), MessageResponse.CUSTOMER, "hello", Instant.parse("2026-10-18T09:01:00Z"));

        redis.publish(CHANNEL, json.writeValueAsString(view));

        assertThat(framesSentTo(customerSocket)).isEmpty();
        verify(nodeA.stomp).convertAndSend(eq("/topic/chat/" + otherBooking), eq((Object) view));
    }

    @Test
    void aRecipientOnTheOtherNodeIsPushedOnlyOnceThatNodesPresenceLapses() throws Exception {
        WebSocketSession customerSocket = nodeA.open(customer);
        nodeB.open(provider);

        nodeA.send(customerSocket, "are you near?");
        assertThat(push.pushes.get()).isZero();

        // Node B dies: its socket never closes and it stops beating; node A keeps beating.
        clock.advance(TTL.dividedBy(2));
        nodeA.presence.heartbeat();
        clock.advance(TTL.dividedBy(2));
        nodeA.presence.heartbeat();

        nodeA.send(customerSocket, "hello?");
        assertThat(push.pushes.get()).isEqualTo(1);
        assertThat(push.lastRecipient).isEqualTo(provider);
        assertThat(push.lastBooking).isEqualTo(booking);
    }

    @Test
    void anUnreadableMessageOnTheChannelIsDropped() throws Exception {
        WebSocketSession customerSocket = nodeA.open(customer);

        assertThatCode(() -> redis.publish(CHANNEL, "not json")).doesNotThrowAnyException();
        assertThatCode(() -> redis.publish(CHANNEL, "{\"body\":\"no booking\"}")).doesNotThrowAnyException();

        assertThat(framesSentTo(customerSocket)).isEmpty();
        verify(nodeA.stomp, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void withRedisDownASendIsStillStoredAndTheRecipientPushed() throws Exception {
        WebSocketSession customerSocket = nodeA.open(customer);
        nodeB.open(provider);
        redis.down = true;

        assertThatCode(() -> nodeA.send(customerSocket, "anyone?")).doesNotThrowAnyException();

        assertThat(store.messages).hasSize(1);
        assertThat(framesSentTo(customerSocket)).isEmpty();
        // Presence cannot be read, so the recipient is pushed rather than risk silence.
        assertThat(push.pushes.get()).isEqualTo(1);
        verify(customerSocket, never()).close(any());
    }

    @Test
    void insideATransactionTheMessageIsPublishedOnlyAfterCommit() {
        RedisMessageDeliveryAdapter delivery = new RedisMessageDeliveryAdapter(redis.template(), json, CHANNEL);
        ChatChannel channel = store.findChannel(booking).orElseThrow();
        ChatMessage message = ChatMessage.create(booking, customer, "hi", clock.instant(),
                CREATED.plus(Duration.ofDays(90)));

        TransactionSynchronizationManager.initSynchronization();
        try {
            delivery.deliver(message, channel);
            assertThat(published()).isEmpty();

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertThat(published()).singleElement()
                    .satisfies(frame -> assertThat(frame).contains(message.getId().toString()));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
