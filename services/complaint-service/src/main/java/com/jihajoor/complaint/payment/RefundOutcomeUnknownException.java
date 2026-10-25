package com.jihajoor.complaint.payment;

/**
 * Thrown by a {@link RefundPort} when the Payment Service gave no definitive answer to a refund
 * request: it could not be reached, timed out, answered 5xx or {@code REFUND_OUTCOME_UNKNOWN}
 * (the gateway did not confirm), refused this service's credential, or answered something this
 * client does not understand (Requirement 16.5).
 *
 * <p>It is deliberately not a {@link RefundResult#rejected rejection}: money may have moved (or may
 * still move), so the complaint refund must neither be recorded FAILED, which would hand it to
 * Finance_Admin as "not refunded" and move the complaint to REFUND_FAILED, nor SUCCEEDED. It stays
 * PENDING, and because every attempt carries the same idempotency key, re-sending it is safe: the
 * Payment Service replays a refund that executed and re-sends a PENDING one under its original
 * gateway refund id.
 */
public class RefundOutcomeUnknownException extends RuntimeException {

    public RefundOutcomeUnknownException(String message) {
        super(message);
    }

    public RefundOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
