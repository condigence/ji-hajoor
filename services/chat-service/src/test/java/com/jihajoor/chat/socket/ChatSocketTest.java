package com.jihajoor.chat.socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jihajoor.chat.delivery.LocalMessageFanout;
import com.jihajoor.chat.delivery.WebSocketMessageDeliveryAdapter;
import com.jihajoor.chat.domain.ChatChannel;
import com.jihajoor.chat.presence.InMemoryPresenceRegistry;
import com.jihajoor.chat.service.ChatService;
import com.jihajoor.chat.support.TestDoubles.InMemoryChatStore;
import com.jihajoor.chat.support.TestDoubles.RecordingPushPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * The apps' chat WebSocket (Requirement 18.2, 18.3, 18.5, 18.7): who may open it, and that a frame
 * from one participant is stored, masked and pushed to both open sockets.
 */
class ChatSocketTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T09:00:00Z");

    private final UUID booking = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

    private InMemoryChatStore store;
    private InMemoryPresenceRegistry presence;
    private RecordingPushPort push;
    private ChatSocketSessions sessions;
    private ChatSocketHandler handler;
    private ChatSocketHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        store = new InMemoryChatStore();
        store.saveChannel(ChatChannel.activate(booking, customer, provider, CREATED, CREATED));
        presence = new InMemoryPresenceRegistry();
        push = new RecordingPushPort();
        sessions = new ChatSocketSessions();
        JsonMapper json = JsonMapper.builder().build();
        ChatService chat = new ChatService(store,
                new WebSocketMessageDeliveryAdapter(
                        new LocalMessageFanout(mock(SimpMessagingTemplate.class), sessions, json)),
                presence, push, Duration.ofDays(90), Clock.systemUTC());
        handler = new ChatSocketHandler(chat, sessions, presence, json);
        interceptor = new ChatSocketHandshakeInterceptor(store);
    }

    // ----- Handshake -----------------------------------------------------------------------------

    private record Handshake(boolean accepted, int status, Map<String, Object> attributes) {
    }

    private Handshake handshake(String path, UUID user) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", path);
        if (user != null) {
            Principal principal = user::toString;
            servletRequest.setUserPrincipal(principal);
        }
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        boolean accepted = interceptor.beforeHandshake(new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse), mock(WebSocketHandler.class), attributes);
        return new Handshake(accepted, servletResponse.getStatus(), attributes);
    }

    private String socketPath(UUID bookingId) {
        return "/chat/channels/" + bookingId + "/ws";
    }

    @Test
    void participantsMayOpenTheSocket() {
        for (UUID user : List.of(customer, provider)) {
            Handshake result = handshake(socketPath(booking), user);
            assertThat(result.accepted()).isTrue();
            assertThat(result.attributes())
                    .containsEntry(ChatSocketHandler.BOOKING_ATTR, booking)
                    .containsEntry(ChatSocketHandler.USER_ATTR, user)
                    .containsEntry(ChatSocketHandler.ACTIVE_ATTR, true);
        }
    }

    @Test
    void aNonParticipantIsForbidden() {
        Handshake result = handshake(socketPath(booking), UUID.randomUUID());
        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo(403);
    }

    @Test
    void anUnauthenticatedUpgradeIsUnauthorized() {
        Handshake result = handshake(socketPath(booking), null);
        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo(401);
    }

    @Test
    void anUnknownOrMalformedBookingIsNotFound() {
        assertThat(handshake(socketPath(UUID.randomUUID()), customer).status()).isEqualTo(404);
        assertThat(handshake("/chat/channels/not-a-uuid/ws", customer).status()).isEqualTo(404);
    }

    // ----- Messaging -----------------------------------------------------------------------------

    private WebSocketSession openSession(UUID user, boolean active) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ChatSocketHandler.BOOKING_ATTR, booking);
        attributes.put(ChatSocketHandler.USER_ATTR, user);
        attributes.put(ChatSocketHandler.ACTIVE_ATTR, active);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);
        return session;
    }

    private static List<String> framesSentTo(WebSocketSession session) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, org.mockito.Mockito.atLeast(0)).sendMessage(captor.capture());
        List<String> frames = new ArrayList<>();
        for (WebSocketMessage<?> m : captor.getAllValues()) {
            frames.add(((TextMessage) m).getPayload());
        }
        return frames;
    }

    @Test
    void aFrameIsStoredMaskedAndDeliveredToBothParticipants() throws Exception {
        WebSocketSession customerSocket = openSession(customer, true);
        WebSocketSession providerSocket = openSession(provider, true);

        handler.handleMessage(customerSocket, new TextMessage("{\"body\":\"  call 9876543210  \"}"));

        assertThat(store.messages).singleElement()
                .satisfies(m -> assertThat(m.getBody()).doesNotContain("9876543210"));
        for (WebSocketSession socket : List.of(customerSocket, providerSocket)) {
            assertThat(framesSentTo(socket)).singleElement().satisfies(frame -> {
                assertThat(frame).contains("\"senderRole\":\"CUSTOMER\"");
                assertThat(frame).contains("\"channelId\":\"" + booking + "\"");
                assertThat(frame).doesNotContain("9876543210");
            });
        }
        // The provider is connected, so no push notification is needed (Requirement 18.3).
        assertThat(push.pushes.get()).isZero();
    }

    @Test
    void aRecipientWithoutAnOpenSocketIsPushed() throws Exception {
        WebSocketSession customerSocket = openSession(customer, true);

        handler.handleMessage(customerSocket, new TextMessage("{\"body\":\"on my way?\"}"));

        assertThat(push.pushes.get()).isEqualTo(1);
        assertThat(push.lastRecipient).isEqualTo(provider);
    }

    @Test
    void closingTheLastSocketMarksTheUserOffline() throws Exception {
        WebSocketSession first = openSession(provider, true);
        WebSocketSession second = openSession(provider, true);
        assertThat(presence.isOnline(booking, provider)).isTrue();

        handler.afterConnectionClosed(first, CloseStatus.GOING_AWAY);
        assertThat(presence.isOnline(booking, provider)).isTrue();

        handler.afterConnectionClosed(second, CloseStatus.GOING_AWAY);
        assertThat(presence.isOnline(booking, provider)).isFalse();
    }

    @Test
    void malformedBlankAndOversizedFramesAreDropped() throws Exception {
        WebSocketSession customerSocket = openSession(customer, true);

        handler.handleMessage(customerSocket, new TextMessage("not json"));
        handler.handleMessage(customerSocket, new TextMessage("{\"body\":\"   \"}"));
        handler.handleMessage(customerSocket, new TextMessage("{\"body\":42}"));
        handler.handleMessage(customerSocket, new TextMessage(
                "{\"body\":\"" + "x".repeat(ChatSocketHandler.MAX_BODY_CHARS + 1) + "\"}"));

        assertThat(store.messages).isEmpty();
        verify(customerSocket, never()).close(any());
    }

    @Test
    void aClosedChannelEndsTheSocketNormally() throws Exception {
        WebSocketSession late = openSession(customer, false);

        verify(late).close(ChatSocketHandler.CHANNEL_CLOSED);
        assertThat(ChatSocketHandler.CHANNEL_CLOSED.getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
        assertThat(presence.isOnline(booking, customer)).isFalse();
    }

    @Test
    void aSendAfterTheBookingEndsClosesTheSocketNormally() throws Exception {
        WebSocketSession customerSocket = openSession(customer, true);
        store.findChannel(booking).orElseThrow().deactivate(Instant.now());

        handler.handleMessage(customerSocket, new TextMessage("{\"body\":\"still there?\"}"));

        assertThat(store.messages).isEmpty();
        verify(customerSocket).close(ChatSocketHandler.MESSAGE_NOT_DELIVERED);
        assertThat(ChatSocketHandler.MESSAGE_NOT_DELIVERED.getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
    }

    @Test
    void bookingIdIsReadOnlyFromTheExactSocketPath() {
        assertThat(ChatSocketHandshakeInterceptor.bookingIdFrom(socketPath(booking))).isEqualTo(booking);
        assertThat(ChatSocketHandshakeInterceptor.bookingIdFrom("/chat/channels/" + booking + "/messages")).isNull();
        assertThat(ChatSocketHandshakeInterceptor.bookingIdFrom(null)).isNull();
    }
}
