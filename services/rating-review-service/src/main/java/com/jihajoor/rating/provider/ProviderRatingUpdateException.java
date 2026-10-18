package com.jihajoor.rating.provider;

/**
 * A provider rating update the Provider Service did not confirm. {@link #isPermanent()} is
 * {@code true} when it refused the update for good (an unknown provider, an invalid aggregate), so
 * re-sending the same aggregate can never succeed; otherwise (unreachable, timed out, 5xx, a
 * credential problem an operator will fix) the update stays owed and is re-sent.
 */
public class ProviderRatingUpdateException extends RuntimeException {

    private final boolean permanent;

    public ProviderRatingUpdateException(String message, Throwable cause, boolean permanent) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
