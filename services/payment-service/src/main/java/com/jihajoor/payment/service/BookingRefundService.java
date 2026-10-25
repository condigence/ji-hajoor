package com.jihajoor.payment.service;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jihajoor.payment.domain.PaymentRefund;
import com.jihajoor.payment.domain.PaymentRefundRepository;
import com.jihajoor.payment.domain.PaymentTransaction;
import com.jihajoor.payment.domain.PaymentTransactionRepository;
import com.jihajoor.payment.domain.TransactionStatus;

/**
 * Refunds a booking's payment on behalf of another service (Requirement 12.7, 16.5): the Complaint
 * Service knows the booking a complaint is about, not the payment transaction, so this resolves the
 * booking's captured payment and hands the refund to {@link PaymentService#refund}.
 *
 * <h2>The refund is the existing refund</h2>
 * Nothing about moving money is decided here. {@link PaymentService#refund} still does all of it:
 * the two-decimal and over-refund checks against what was captured, the PENDING reservation that is
 * committed before the gateway is called, the unique {@code refund:<transactionId>:<key>} that makes
 * a same-key retry replay instead of refunding twice, the gateway idempotency key (the refund id,
 * which Razorpay de-duplicates through the refund's {@code receipt}), and the
 * {@code 502 REFUND_OUTCOME_UNKNOWN} answer that leaves the refund PENDING until a same-key retry or
 * a Finance_Admin reconcile settles it. Every refusal it raises reaches the caller unchanged.
 *
 * <h2>Which payment</h2>
 * A booking keeps one transaction per payment attempt, and a new attempt is only opened once every
 * earlier one FAILED, so at most one attempt was captured. The refund targets the attempt that is
 * SUCCESS, PARTIALLY_REFUNDED or REFUNDED; REFUNDED is included so that a retry of a refund that
 * refunded the whole amount still finds its transaction and is replayed rather than refused. Should
 * more than one attempt ever be captured, the one already holding a refund under this key is used
 * (a replay), and otherwise the request is refused with 409 {@code AMBIGUOUS_BOOKING_PAYMENT} rather
 * than guessing which charge to return money from.
 */
@Service
public class BookingRefundService {

    private static final Logger log = LoggerFactory.getLogger(BookingRefundService.class);

    /** Transaction states in which money was captured, i.e. a refund under a key may exist or start. */
    static final Set<TransactionStatus> CAPTURED = EnumSet.of(TransactionStatus.SUCCESS,
            TransactionStatus.PARTIALLY_REFUNDED, TransactionStatus.REFUNDED);

    private final PaymentTransactionRepository transactionRepository;
    private final PaymentRefundRepository refundRepository;
    private final PaymentService paymentService;

    public BookingRefundService(PaymentTransactionRepository transactionRepository,
                                PaymentRefundRepository refundRepository,
                                PaymentService paymentService) {
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
        this.paymentService = paymentService;
    }

    /**
     * Refunds {@code amount} of the booking's captured payment under {@code idempotencyKey}.
     *
     * @param reason why the refund is issued; logged with the key (the refund record has no column
     *               for it), before the gateway is called so the line exists even when the outcome
     *               is lost
     * @return the transaction after the refund and the SUCCEEDED refund record
     * @throws PaymentException 404 {@code PAYMENT_NOT_FOUND} when no payment was ever taken for the
     *                          booking, 409 {@code PAYMENT_NOT_REFUNDABLE} when none was captured, 409
     *                          {@code AMBIGUOUS_BOOKING_PAYMENT} (see the class Javadoc), and every
     *                          refusal of {@link PaymentService#refund}
     */
    public BookingRefund refundBooking(UUID bookingId, BigDecimal amount, String idempotencyKey,
                                       String reason) {
        if (bookingId == null) {
            throw PaymentException.validation("A booking id is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw PaymentException.validation("A refund idempotency key is required");
        }
        PaymentTransaction target = capturedPayment(bookingId, idempotencyKey);
        log.info("Internal refund requested: booking={} transaction={} amount={} idempotencyKey={} "
                + "reason={}", bookingId, target.getId(), amount, idempotencyKey, reason);

        PaymentTransaction after = paymentService.refund(target.getId(), amount, idempotencyKey);

        // refund() only returns normally once the refund under this key has SUCCEEDED (fresh, a
        // re-send of a PENDING one, or a replay), so its record is there to report.
        String key = IdempotencyKeys.forRefund(target.getId(), idempotencyKey);
        PaymentRefund refund = refundRepository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("Refund " + key + " succeeded but has no record"));
        return new BookingRefund(after, refund);
    }

    private PaymentTransaction capturedPayment(UUID bookingId, String idempotencyKey) {
        List<PaymentTransaction> attempts = transactionRepository.findByBookingId(bookingId);
        if (attempts.isEmpty()) {
            throw new PaymentException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                    "No payment was taken for booking " + bookingId);
        }
        List<PaymentTransaction> captured = attempts.stream()
                .filter(t -> CAPTURED.contains(t.getStatus()))
                .toList();
        if (captured.isEmpty()) {
            throw new PaymentException(HttpStatus.CONFLICT, "PAYMENT_NOT_REFUNDABLE",
                    "Booking " + bookingId + " has no captured payment to refund (attempts: "
                            + attempts.stream().map(t -> t.getStatus().name()).toList() + ")");
        }
        if (captured.size() == 1) {
            return captured.get(0);
        }
        Optional<PaymentTransaction> alreadyRefundedUnderKey = captured.stream()
                .filter(t -> refundRepository.findByIdempotencyKey(
                        IdempotencyKeys.forRefund(t.getId(), idempotencyKey)).isPresent())
                .findFirst();
        return alreadyRefundedUnderKey.orElseThrow(() -> new PaymentException(HttpStatus.CONFLICT,
                "AMBIGUOUS_BOOKING_PAYMENT", "Booking " + bookingId + " has " + captured.size()
                        + " captured payments; Finance_Admin must refund the right one by transaction"));
    }

    /** The outcome of {@link #refundBooking}: the refunded transaction and its SUCCEEDED refund. */
    public record BookingRefund(PaymentTransaction transaction, PaymentRefund refund) {
    }
}
