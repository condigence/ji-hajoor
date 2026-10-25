package com.jihajoor.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.jihajoor.gateway.auth.IntrospectionResult;
import com.jihajoor.gateway.config.GatewaySecurityProperties;
import com.jihajoor.gateway.ratelimit.RateLimitCounter;
import com.jihajoor.gateway.support.GatewayErrorWriter;

import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Per-IP limits for signed-out traffic, with a tighter budget for sign-in and sign-up calls. */
class AnonymousIpRateLimitGatewayFilterTest {

    private final AtomicInteger forwarded = new AtomicInteger();
    private final GatewayFilterChain chain = ex -> {
        forwarded.incrementAndGet();
        return Mono.empty();
    };
    private AnonymousIpRateLimitGatewayFilter filter;

    @BeforeEach
    void setUp() {
        GatewaySecurityProperties props = new GatewaySecurityProperties();
        props.getRateLimit().setAnonymousPerIpPerMinute(10);
        props.getRateLimit().setCredentialPerIpPerMinute(3);
        ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();
        RateLimitCounter counter = (String key, Duration window) ->
                Mono.just(counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet());
        filter = new AnonymousIpRateLimitGatewayFilter(props, counter, new GatewayErrorWriter(new JsonMapper()));
    }

    private HttpStatus send(String path, String ip, boolean signedIn) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path).header("X-Real-IP", ip).build());
        if (signedIn) {
            exchange.getAttributes().put(JwtIntrospectionGatewayFilter.INTROSPECTION_ATTR,
                    new IntrospectionResult(true, "user-1", List.of("CUSTOMER")));
        }
        filter.filter(exchange, chain).block();
        return exchange.getResponse().getStatusCode() == null
                ? HttpStatus.OK : HttpStatus.valueOf(exchange.getResponse().getStatusCode().value());
    }

    @Test
    void signInAttemptsFromOneAddressAreCappedButOtherAddressesAreNot() {
        for (int i = 0; i < 3; i++) {
            assertThat(send("/auth/login/password", "203.0.113.7", false)).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(send("/auth/login/password", "203.0.113.7", false)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(send("/auth/register/verify", "203.0.113.7", false)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(send("/auth/login/password", "198.51.100.2", false)).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void otherSignedOutTrafficHasTheWiderBudget_andSignedInTrafficIsLeftToThePerUserLimit() {
        for (int i = 0; i < 10; i++) {
            assertThat(send("/catalog/categories", "203.0.113.9", false)).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(send("/catalog/categories", "203.0.113.9", false)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(send("/bookings", "203.0.113.9", true)).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(send("/health", "203.0.113.9", false)).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void withoutAVisitorAddressNothingIsLimited_soVisitorsNeverShareOneBucket() {
        for (int i = 0; i < 20; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/auth/login/password").build());
            filter.filter(exchange, chain).block();
            assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
