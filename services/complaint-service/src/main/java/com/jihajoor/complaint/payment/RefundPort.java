package com.jihajoor.complaint.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Submits refund requests to the Payment Service on behalf of a complaint (Requirement 16.5).
 * Modelled as a port so the transport can vary and so it is mockable in unit tests. The production
 * adapter is {@link HttpRefundAdapter} ({@code POST /internal/payments/refunds}); the
 * {@link LoggingRefundAdapter} moves no money and is for local runs only.
 *
 * <p>Three outcomes, kept apart on purpose:
 * <ul>
 *   <li>{@link RefundResult#approved(String) approved}: the Payment Service executed the refund.</li>
 *   <li>{@link RefundResult#rejected(String) rejected}: it refused, and no money moved; the caller
 *       drives the REFUND_FAILED flow (Requirement 16.6).</li>
 *   <li>a thrown exception, normally {@link RefundOutcomeUnknownException}: there was no
 *       definitive answer and money may or may not have moved. The caller must record neither
 *       success nor failure; the refund stays PENDING and is re-sent under the same key.</li>
 * </ul>
 *
 * <p>The caller invokes this port with <strong>no database transaction open</strong>, after the
 * PENDING refund record has committed. {@code idempotencyKey} is stable for that record (at most 64
 * characters), so a re-send of the same refund is de-duplicated by the Payment Service, which
 * executes at most one refund per key.
 */
public interface RefundPort {

    /**
     * Requests a refund of {@code amount} for the booking associated with a complaint.
     *
     * @param idempotencyKey stable key for this refund; the Payment Service executes at most one
     *                       refund per key
     * @return the {@link RefundResult}; {@link RefundResult#approved()} is {@code false} when the
     *         Payment Service rejects the request.
     * @throws RefundOutcomeUnknownException when the outcome is unknown
     */
    RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount,
                               String idempotencyKey);
}
