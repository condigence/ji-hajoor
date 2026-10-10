package com.jihajoor.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jihajoor.gateway.auth.IntrospectionResult;
import com.jihajoor.gateway.support.GatewayErrorWriter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The {@code ?access_token=} fallback for browser SSE/WebSocket connections: accepted only on the
 * realtime paths, forwarded as a bearer header, and stripped from the forwarded URL.
 */
class JwtIntrospectionGatewayFilterQueryTokenTest {

    private static final String GOOD = "good-token";

    private final JwtIntrospectionGatewayFilter filter = new JwtIntrospectionGatewayFilter(
            token -> Mono.just(GOOD.equals(token)
                    ? new IntrospectionResult(true, "user-1", List.of("CUSTOMER"), Instant.now().plusSeconds(600))
                    : IntrospectionResult.inactive()),
            new GatewayErrorWriter(new ObjectMapper()));

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        forwarded.set(exchange);
        return Mono.empty();
    };

    @Test
    void locationStream_acceptsQueryToken_andForwardsItAsHeaderOnly() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/locations/3f1c/stream?access_token=" + GOOD + "&x=1"));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer " + GOOD);
        assertThat(forwarded.get().getRequest().getURI().getQuery()).isEqualTo("x=1");
    }

    @Test
    void chatSocket_acceptsQueryToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/chat/channels/abc/ws?access_token=" + GOOD));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
    }

    @Test
    void ordinaryApi_ignoresQueryToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/bookings/history?access_token=" + GOOD));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidQueryToken_isRejected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/locations/3f1c/stream?access_token=forged"));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void headerToken_stillWorksAndWins() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/locations/3f1c/stream?access_token=forged")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GOOD));

        filter.filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
    }
}
