package com.jihajoor.gateway.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Answers {@code GET /} with a small JSON status instead of Spring's "Whitelabel Error Page".
 *
 * <p>The gateway only proxies API prefixes ({@code /auth/**}, {@code /bookings/**}, ...), so a
 * browser opening the bare deployment URL used to get a 404. This handler is local to the
 * gateway, not a proxied route, so the global gateway filters (JWT, rate limit) do not apply.
 */
@Configuration
public class RootRouteConfig {

    @Bean
    public RouterFunction<ServerResponse> rootRoute() {
        return RouterFunctions.route()
                .GET("/", request -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of(
                                "service", "Ji-Hajoor API Gateway",
                                "status", "UP",
                                "health", "/health")))
                .build();
    }
}
