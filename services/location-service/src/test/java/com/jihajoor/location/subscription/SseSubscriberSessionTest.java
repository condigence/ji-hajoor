package com.jihajoor.location.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.jihajoor.location.domain.Coordinates;

class SseSubscriberSessionTest {

    private final LocationUpdatePush push = new LocationUpdatePush(new Coordinates(25.5, 84.6), 4);

    @Test
    void sendsEachUpdateAsALocationEvent_andClosesTheEmitter() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        SseSubscriberSession session = new SseSubscriberSession("s-1", emitter);

        session.send(push);
        session.close();

        assertThat(session.sessionId()).isEqualTo("s-1");
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void aGoneClientSurfacesAsASendFailure() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertThatThrownBy(() -> new SseSubscriberSession("s-2", emitter).send(push))
                .isInstanceOf(SseSubscriberSession.SubscriberSendException.class)
                .hasMessageContaining("s-2");
    }
}
