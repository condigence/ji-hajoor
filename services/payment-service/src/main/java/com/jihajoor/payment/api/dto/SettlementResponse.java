package com.jihajoor.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.jihajoor.payment.domain.Settlement;
import com.jihajoor.payment.domain.SettlementStatus;

/** API view of a {@link Settlement}. Never exposes the encrypted bank account reference. */
public record SettlementResponse(
        UUID id,
        UUID providerId,
        BigDecimal amount,
        SettlementStatus status,
        Instant requestedAt,
        Instant updatedAt) {

    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(
                s.getId(), s.getProviderId(), s.getAmount(), s.getStatus(),
                s.getRequestedAt(), s.getUpdatedAt());
    }
}
