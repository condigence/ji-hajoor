package com.jihajoor.complaint.domain;

/**
 * Lifecycle of the single refund a complaint may carry (Requirement 16.5, 16.6).
 *
 * <pre>
 *   PENDING -&gt; SUCCEEDED | FAILED
 * </pre>
 *
 * <p>{@link #PENDING} is written and committed <em>before</em> the Payment Service is asked to move
 * money, so a refund that is in flight (or whose outcome was lost to a timeout or a crash) is always
 * visible and blocks a second refund of the same complaint. PENDING is also the honest state of a
 * refund whose outcome is unknown: it is never guessed as SUCCEEDED or FAILED, but re-sent under
 * the same Payment Service idempotency key, by a same-key retry of the approval or by
 * {@code POST /complaints/{id}/refund/resend} (or reconciled by Finance_Admin), until the Payment
 * Service gives a definitive answer.
 */
public enum ComplaintRefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}
