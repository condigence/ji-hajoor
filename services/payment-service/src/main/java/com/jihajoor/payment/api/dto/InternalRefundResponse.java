package com.jihajoor.payment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.jihajoor.payment.service.BookingRefundService.BookingRefund;

/**
 * Response of {@code POST /internal/payments/refunds}: the executed refund (Requirement 12.7, 16.5).
 * Only a refund the gateway executed is answered 200, so {@code status} is always
 * {@code SUCCEEDED}; a refund whose outcome is unknown is a 502 {@code REFUND_OUTCOME_UNKNOWN} and
 * a refused one is a 4xx or 502 {@code REFUND_GATEWAY_ERROR} in the shared error envelope.
 *
 * @param refundId                 the Payment Service refund id, also the gateway idempotency key
 * @param transactionId            the refunded payment transaction
 * @param bookingId                the booking it paid for
 * @param amount                   the amount this refund returned
 * @param status                   the refund's status ({@code SUCCEEDED})
 * @param gatewayRefundReference   the gateway's reference for the refund
 * @param transactionStatus        the transaction afterwards: PARTIALLY_REFUNDED or REFUNDED
 * @param transactionRefundedAmount the transaction's refunded total afterwards, all refunds included
 */
public record InternalRefundResponse(
        UUID refundId,
        UUID transactionId,
        UUID bookingId,
        BigDecimal amount,
        String status,
        String gatewayRefundReference,
        String transactionStatus,
        BigDecimal transactionRefundedAmount) {

    public static InternalRefundResponse from(BookingRefund outcome) {
        return new InternalRefundResponse(
                outcome.refund().getId(),
                outcome.transaction().getId(),
                outcome.transaction().getBookingId(),
                outcome.refund().getAmount(),
                outcome.refund().getStatus().name(),
                outcome.refund().getGatewayReference(),
                outcome.transaction().getStatus().name(),
                outcome.transaction().getRefundedAmount());
    }
}
