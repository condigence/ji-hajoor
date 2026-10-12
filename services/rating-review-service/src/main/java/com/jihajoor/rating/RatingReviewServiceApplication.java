package com.jihajoor.rating;

import com.jihajoor.rating.config.RatingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point for the Ji-Hajoor Rating &amp; Review Service.
 *
 * <p>Consumes {@code PaymentCompleted} idempotently and opens 7-day review prompts for both the
 * customer and the provider (Requirement 15.1, 15.10). Accepts reviews within the open window
 * (Property 15), recomputes the provider's weighted aggregate (recent reviews weighted 1.5x,
 * rounded to 2 dp — Property 16), applies fraud detection that holds suspicious reviews out of the
 * aggregate until Admin approval (Property 17), publishes {@code ReviewSubmitted} for non-flagged
 * reviews, auto-flags providers UNDER_REVIEW when the aggregate drops below 3.0, and supports Admin
 * moderation with Audit_Log entries (Requirement 15).
 *
 * <p>The shared security, observability, and outbox libraries auto-configure from the classpath.
 * The shared outbox JPA entities/repositories live in {@code com.jihajoor.shared.outbox}, so both
 * they and this service's own persistence packages are explicitly scanned.
 */
@SpringBootApplication
@EnableConfigurationProperties(RatingProperties.class)
// One base package per source root, not one class per entity/repository:
// ProcessedEventRepository sits in the com.jihajoor.shared.outbox.kafka SUBpackage, so
// naming both classes scans that subpackage twice and the duplicate bean definition
// fails startup.
@EntityScan(basePackages = {"com.jihajoor.rating", "com.jihajoor.shared.outbox"})
@EnableJpaRepositories(basePackages = {"com.jihajoor.rating", "com.jihajoor.shared.outbox"})
public class RatingReviewServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RatingReviewServiceApplication.class, args);
    }
}
