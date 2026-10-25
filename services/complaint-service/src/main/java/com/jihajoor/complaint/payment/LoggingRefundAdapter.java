package com.jihajoor.complaint.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LOCAL/DEV ONLY {@link RefundPort} that logs and approves refund requests; it <strong>moves no
 * money</strong>, yet the complaint refund is recorded SUCCEEDED. Selected only explicitly, with
 * {@code jihajoor.complaint.payment.client=logging} ({@code COMPLAINT_PAYMENT_CLIENT=logging}), for
 * running the Complaint Service without a Payment Service; deployments use
 * {@link HttpRefundAdapter}, the default. A WARN is logged at startup and on every refund so it
 * cannot go unnoticed.
 *
 * <p>Mirrors the Payment Service's idempotency: the stub reference is derived from the key, so the
 * same key always yields the same reference.
 */
@Component
@ConditionalOnProperty(name = "jihajoor.complaint.payment.client", havingValue = "logging")
public class LoggingRefundAdapter implements RefundPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingRefundAdapter.class);

    public LoggingRefundAdapter() {
        log.warn("Complaint refunds use the LOGGING adapter (jihajoor.complaint.payment.client=logging): "
                + "approved refunds are recorded as SUCCEEDED but no money is returned to customers");
    }

    @Override
    public RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount,
                                      String idempotencyKey) {
        log.warn("REFUND complaint={} booking={} amount={} key={} approved by the logging adapter; "
                + "NO MONEY MOVED", complaintId, bookingId, amount, idempotencyKey);
        String reference = UUID.nameUUIDFromBytes(
                String.valueOf(idempotencyKey).getBytes(StandardCharsets.UTF_8)).toString();
        return RefundResult.approved("stub_rf_" + reference);
    }
}
