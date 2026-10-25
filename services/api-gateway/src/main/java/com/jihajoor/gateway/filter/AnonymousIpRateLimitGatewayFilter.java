package com.jihajoor.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.jihajoor.gateway.auth.IntrospectionResult;
import com.jihajoor.gateway.config.GatewaySecurityProperties;
import com.jihajoor.gateway.ratelimit.RateLimitCounter;
import com.jihajoor.gateway.support.GatewayErrorWriter;

import reactor.core.publisher.Mono;

/**
 * Per-IP limits for signed-out requests, which {@link RateLimitGatewayFilter} (per user) cannot
 * see. Two budgets per client IP and minute: every signed-out request, and a tighter one for
 * sign-in, sign-up and password calls. The account lockouts stop guessing one account's
 * password or code; this slows an attacker spreading guesses over many accounts.
 *
 * <p>The client IP is {@code X-Real-IP}, set by the web apps' nginx from the edge's headers. It is
 * hashed into the counter key, so no address is stored in Redis.
 * Health and metrics paths are never counted.
 */
@Component
public class AnonymousIpRateLimitGatewayFilter implements GlobalFilter, Ordered {

    /** After JWT introspection (which tells signed-in from signed-out), before per-user limits. */
    public static final int ORDER = -55;

    static final List<String> CREDENTIAL_PREFIXES = List.of(
            "/auth/login", "/auth/register", "/auth/password", "/auth/invitations");

    private static final List<String> EXEMPT_PREFIXES = List.of(
            "/health", "/prometheus", "/metrics", "/actuator");

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final GatewaySecurityProperties properties;
    private final RateLimitCounter counter;
    private final GatewayErrorWriter errorWriter;

    public AnonymousIpRateLimitGatewayFilter(GatewaySecurityProperties properties, RateLimitCounter counter,
                                             GatewayErrorWriter errorWriter) {
        this.properties = properties;
        this.counter = counter;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Object attr = exchange.getAttribute(JwtIntrospectionGatewayFilter.INTROSPECTION_ATTR);
        if (attr instanceof IntrospectionResult result && result.active()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        if (matches(path, EXEMPT_PREFIXES)) {
            return chain.filter(exchange);
        }
        String ip = clientIp(exchange);
        if (ip == null) {
            return chain.filter(exchange);
        }
        GatewaySecurityProperties.RateLimit limits = properties.getRateLimit();
        boolean credential = matches(path, CREDENTIAL_PREFIXES);
        Mono<Boolean> allowed = check("rl:anon:" + hash(ip), limits.getAnonymousPerIpPerMinute());
        if (credential) {
            allowed = allowed.flatMap(ok -> ok
                    ? check("rl:cred:" + hash(ip), limits.getCredentialPerIpPerMinute())
                    : Mono.just(false));
        }
        return allowed.flatMap(ok -> ok
                ? chain.filter(exchange)
                : errorWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                        "Too many requests from your network. Try again in a minute.", WINDOW.toSeconds()));
    }

    private Mono<Boolean> check(String key, int limit) {
        if (limit <= 0) {
            return Mono.just(true);
        }
        return counter.incrementAndGet(key, WINDOW).map(count -> count <= limit);
    }

    /**
     * The visitor's address from {@code X-Real-IP}, which the web apps' nginx always sets. Without
     * it the request came from inside the platform or straight to the gateway: the socket address
     * would then be a proxy shared by every visitor, so no address is used and the IP limits do
     * not apply (the per-account lockouts still do).
     */
    static String clientIp(ServerWebExchange exchange) {
        String real = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (real == null || real.isBlank() || real.length() > 64) {
            return null;
        }
        return real.strip();
    }

    private static boolean matches(String path, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String hash(String ip) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
