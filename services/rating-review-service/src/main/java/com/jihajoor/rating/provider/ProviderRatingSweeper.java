package com.jihajoor.rating.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-sends provider ratings the Provider Service has not confirmed (Requirements 4.7,
 * 15.4): the push after a review change failed (Provider Service down, timed out, credential not
 * yet configured) or the process died between the commit and the push. The work lives in
 * {@link ProviderRatingSync#pushOwed()} so it can be unit-tested without a scheduler.
 *
 * <p>Every instance sweeps; two instances pushing the same provider is harmless, since the push is
 * an idempotent, sequence-ordered set.
 */
@Component
public class ProviderRatingSweeper {

    private static final Logger log = LoggerFactory.getLogger(ProviderRatingSweeper.class);

    private final ProviderRatingSync ratingSync;

    public ProviderRatingSweeper(ProviderRatingSync ratingSync) {
        this.ratingSync = ratingSync;
    }

    /** Runs on a fixed delay; a failed sweep is retried on the next tick. */
    @Scheduled(fixedDelayString = "${jihajoor.rating.provider-sync.sweep-interval:PT1M}",
            initialDelayString = "${jihajoor.rating.provider-sync.sweep-interval:PT1M}")
    public void sweep() {
        try {
            int delivered = ratingSync.pushOwed();
            if (delivered > 0) {
                log.info("Provider rating sweep delivered {} owed rating(s)", delivered);
            }
        } catch (RuntimeException e) {
            log.warn("Provider rating sweep failed; will retry on next tick: {}", e.getMessage());
        }
    }
}
