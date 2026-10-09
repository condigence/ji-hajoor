package com.jihajoor.gateway;

import com.jihajoor.gateway.config.GatewaySecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Ji-Hajoor API Gateway — the secured edge for all client traffic (Requirement 23).
 *
 * <p>Built on Spring Cloud Gateway (reactive). Global filters enforce, in order: HTTPS redirect,
 * X-Correlation-ID propagation, WAF-style OWASP request inspection, JWT introspection against the
 * Auth Service, per-phone OTP throttling, and per-user rate limiting.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
