package com.jihajoor.payment.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /internal/payments/refunds}, the service-to-service refund of a
 * booking's payment (Requirement 12.7, 16.5).
 *
 * @param bookingId      the booking whose captured payment is refunded
 * @param amount         full or partial; at most two decimal places, matching the
 *                       {@code numeric(12,2)} columns, and at most what is still unrefunded
 * @param reason         why the refund is issued; optional, logged with the key
 * @param idempotencyKey stable for the caller's refund (the Complaint Service sends
 *                       {@code complaint-refund:<its refund id>}). Re-sending the same key and amount
 *                       never refunds twice: a SUCCEEDED refund is replayed and a PENDING one is
 *                       re-sent to the gateway under its original refund id. After a
 *                       {@code 502 REFUND_OUTCOME_UNKNOWN}, retry with the <em>same</em> key.
 */
public record InternalRefundRequest(
        @NotNull UUID bookingId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Size(max = 500) String reason,
        @NotBlank @Size(max = 64) String idempotencyKey) {
}
