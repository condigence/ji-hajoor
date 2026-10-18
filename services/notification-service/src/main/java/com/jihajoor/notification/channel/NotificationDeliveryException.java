package com.jihajoor.notification.channel;

/**
 * Thrown by a channel port adapter (SMS, email, push, in-app) when a notification cannot be
 * delivered.
 *
 * <p>A delivery failure is treated as recoverable: the orchestrator retries up to the configured
 * limit with exponential backoff before marking the delivery permanently failed
 * (Requirement 17.8). A {@linkplain #permanent permanent} failure (a malformed address, a message
 * the vendor refuses to parse) would fail the same way on every attempt, so it is marked
 * permanently failed at once, without spending the remaining retries. The exception message is
 * stored in the delivery log's {@code error_description} field but must never contain PII
 * (Requirement 26.4).
 */
public class NotificationDeliveryException extends RuntimeException {

    private final boolean permanent;

    public NotificationDeliveryException(String message) {
        this(message, null, false);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private NotificationDeliveryException(String message, Throwable cause, boolean permanent) {
        super(message, cause);
        this.permanent = permanent;
    }

    /** A failure that retrying cannot fix. */
    public static NotificationDeliveryException permanent(String message, Throwable cause) {
        return new NotificationDeliveryException(message, cause, true);
    }

    /** Whether sending the same message again would fail the same way. */
    public boolean isPermanent() {
        return permanent;
    }
}
