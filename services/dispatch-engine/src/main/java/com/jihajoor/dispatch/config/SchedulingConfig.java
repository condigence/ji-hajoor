package com.jihajoor.dispatch.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the Dispatch Engine, whose only scheduled job is the
 * {@code AcceptanceReconciler} that finishes provider acceptances the dispatch thread could not
 * (Requirement 8.6). {@code jihajoor.dispatch.scheduling.enabled=false} switches it off, for a test
 * context that drives the reconciler itself.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jihajoor.dispatch.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
