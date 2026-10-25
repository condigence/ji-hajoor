package com.jihajoor.complaint.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.jihajoor.complaint.domain.ComplaintRefund;
import com.jihajoor.complaint.domain.ComplaintRefundStatus;

/**
 * The recorded refund of a complaint (Requirement 16.5, 16.6). {@code status} is SUCCEEDED with the
 * Payment Service {@code externalReference}, FAILED with a {@code failureReason}, or PENDING while
 * the Payment Service has not given a definitive answer.
 *
 * <p>For a PENDING refund, {@code outcomeUnknown} is true once an attempt ended without an answer
 * ({@code failureReason} then says why; Finance_Admin has been alerted), and {@code resendable}
 * says whether {@code POST /complaints/{id}/refund/resend} would re-send it now (false while an
 * attempt is still in flight). Both are false for a SUCCEEDED or FAILED refund.
 * {@code lastAttemptAt} is when the latest Payment Service call started.
 */
public record RefundResponse(
        UUID id,
        UUID complaintId,
        UUID bookingId,
        BigDecimal amount,
        String reason,
        UUID approvedBy,
        String status,
        String externalReference,
        String failureReason,
        Instant requestedAt,
        Instant completedAt,
        Instant lastAttemptAt,
        boolean outcomeUnknown,
        boolean resendable) {

    /**
     * @param resendable whether the refund may be re-sent now, from
     *                   {@code ComplaintService.isRefundResendable}
     */
    public static RefundResponse from(ComplaintRefund refund, boolean resendable) {
        boolean pending = refund.getStatus() == ComplaintRefundStatus.PENDING;
        return new RefundResponse(
                refund.getId(),
                refund.getComplaintId(),
                refund.getBookingId(),
                refund.getAmount(),
                refund.getReason(),
                refund.getApprovedBy(),
                refund.getStatus().name(),
                refund.getExternalReference(),
                refund.getFailureReason(),
                refund.getRequestedAt(),
                refund.getCompletedAt(),
                refund.getLastAttemptAt() != null ? refund.getLastAttemptAt()
                        : refund.getRequestedAt(),
                pending && refund.getFailureReason() != null,
                pending && resendable);
    }
}
