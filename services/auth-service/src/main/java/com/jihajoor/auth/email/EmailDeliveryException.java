package com.jihajoor.auth.email;

/**
 * An email could not be handed over for delivery.
 *
 * <p>The message is logged by the callers, so it must never hold the recipient address, the body,
 * a code or a link: adapters describe the failure by its kind only.
 *
 * <p>{@link #isPermanent()} tells the two kinds of failure apart. A permanent failure (a malformed
 * address, a message the server refuses to parse) fails the same way on every attempt; any other
 * failure (the mail server unreachable or timing out, rejected credentials) may succeed later.
 * Callers keep their own answer either way; the distinction is for logs and for any caller that
 * retries.
 */
public class EmailDeliveryException extends RuntimeException {

    private final boolean permanent;

    /** A failure that may succeed if tried again. */
    public EmailDeliveryException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public EmailDeliveryException(String message, Throwable cause, boolean permanent) {
        super(message, cause);
        this.permanent = permanent;
    }

    /** Whether sending the same message again would fail the same way. */
    public boolean isPermanent() {
        return permanent;
    }
}
