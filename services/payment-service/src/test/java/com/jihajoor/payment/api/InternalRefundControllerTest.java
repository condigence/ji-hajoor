package com.jihajoor.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.jihajoor.payment.alert.FinanceAlertPort;
import com.jihajoor.payment.config.InternalApiKeyFilter;
import com.jihajoor.payment.config.PaymentProperties;
import com.jihajoor.payment.crypto.LocalAesKmsAdapter;
import com.jihajoor.payment.domain.PaymentMethod;
import com.jihajoor.payment.domain.PaymentRefund;
import com.jihajoor.payment.domain.PaymentTransaction;
import com.jihajoor.payment.domain.RefundStatus;
import com.jihajoor.payment.domain.TransactionStatus;
import com.jihajoor.payment.gateway.GatewayChargeRequest;
import com.jihajoor.payment.gateway.GatewayChargeResult;
import com.jihajoor.payment.gateway.GatewayRefundRequest;
import com.jihajoor.payment.gateway.GatewayRefundResult;
import com.jihajoor.payment.gateway.GatewayTransferRequest;
import com.jihajoor.payment.gateway.GatewayTransferResult;
import com.jihajoor.payment.gateway.PaymentGatewayPort;
import com.jihajoor.payment.gateway.PaymentGatewayRegistry;
import com.jihajoor.payment.idempotency.InMemoryIdempotencyStoreAdapter;
import com.jihajoor.payment.invoice.InvoiceTriggerPort;
import com.jihajoor.payment.notification.ProviderNotificationPort;
import com.jihajoor.payment.service.BookingRefundService;
import com.jihajoor.payment.service.PaymentService;
import com.jihajoor.payment.support.InMemoryPaymentRefundRepository;
import com.jihajoor.payment.support.InMemoryPaymentTransactionRepository;
import com.jihajoor.payment.support.InMemorySettlementRepository;
import com.jihajoor.payment.support.MarkingTransactionOperations;
import com.jihajoor.payment.support.RecordingPaymentCompletedPublisher;
import com.jihajoor.payment.wallet.ProviderWalletClientPort;

/**
 * Web-layer tests for {@link InternalRefundController}, the refund the Complaint Service calls
 * (Requirement 12.7, 16.5).
 *
 * <p>The controller runs behind the real {@link InternalApiKeyFilter}, so the tests pin that the
 * endpoint is refused without the internal credential (its only guard: there is no role rule), and
 * the {@link BookingRefundService} and {@link PaymentService} behind it are real, over in-memory
 * persistence and a recording gateway, so the idempotency the Complaint Service relies on (a
 * retried key never refunds twice, an unknown outcome is re-sent under the same refund id) is
 * exercised end to end through HTTP.
 */
class InternalRefundControllerTest {

    private static final String KEY = "internal-test-key";
    private static final String TEST_DATA_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String GATEWAY = "razorpay";

    private InMemoryPaymentTransactionRepository transactions;
    private InMemoryPaymentRefundRepository refunds;
    private RecordingGateway gateway;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        transactions = new InMemoryPaymentTransactionRepository();
        refunds = new InMemoryPaymentRefundRepository();
        gateway = new RecordingGateway();
        PaymentService paymentService = new PaymentService(transactions, refunds,
                new InMemorySettlementRepository(), new PaymentGatewayRegistry(List.of(gateway)),
                new InMemoryIdempotencyStoreAdapter(), new LocalAesKmsAdapter(TEST_DATA_KEY),
                mock(ProviderWalletClientPort.class), mock(InvoiceTriggerPort.class),
                new RecordingPaymentCompletedPublisher(), mock(FinanceAlertPort.class),
                mock(ProviderNotificationPort.class), new PaymentProperties(),
                new MarkingTransactionOperations());
        mvc = MockMvcBuilders
                .standaloneSetup(new InternalRefundController(
                        new BookingRefundService(transactions, refunds, paymentService)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new InternalApiKeyFilter(KEY))
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PaymentTransaction payment(UUID bookingId, String amount, TransactionStatus status) {
        PaymentTransaction tx = PaymentTransaction.initiate("cust:" + UUID.randomUUID() + ":booking:" + bookingId,
                UUID.randomUUID(), bookingId, UUID.randomUUID(), new BigDecimal(amount),
                new BigDecimal("20.00"), PaymentMethod.UPI, GATEWAY, null);
        if (status != TransactionStatus.PENDING) {
            tx.setGatewayReference("order_" + UUID.randomUUID());
            tx.transitionTo(status);
        }
        return transactions.save(tx);
    }

    private PaymentTransaction captured(UUID bookingId, String amount) {
        return payment(bookingId, amount, TransactionStatus.SUCCESS);
    }

    private static MockHttpServletRequestBuilder refund(UUID bookingId, String amount, String key) {
        return post("/internal/payments/refunds")
                .header(InternalApiKeyFilter.HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingId\":\"" + bookingId + "\",\"amount\":" + amount
                        + ",\"reason\":\"complaint refund\",\"idempotencyKey\":\"" + key + "\"}");
    }

    private static String complaintKey() {
        return "complaint-refund:" + UUID.randomUUID();
    }

    // ----- success -----

    @Test
    void fullRefund_returnsTheExecutedRefundAndRefundsTheTransaction() throws Exception {
        UUID booking = UUID.randomUUID();
        PaymentTransaction tx = captured(booking, "100.00");

        mvc.perform(refund(booking, "100.00", complaintKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundId").value(refunds.findAll().get(0).getId().toString()))
                .andExpect(jsonPath("$.transactionId").value(tx.getId().toString()))
                .andExpect(jsonPath("$.bookingId").value(booking.toString()))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.gatewayRefundReference").value(
                        "rfnd_" + refunds.findAll().get(0).getId()))
                .andExpect(jsonPath("$.transactionStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.transactionRefundedAmount").value(100.00));

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.REFUNDED);
        // The gateway is asked under the Payment Service refund id, which it de-duplicates on.
        assertThat(gateway.refunds).singleElement().satisfies(req -> {
            assertThat(req.idempotencyKey()).isEqualTo(refunds.findAll().get(0).getId().toString());
            assertThat(req.gatewayChargeReference()).isEqualTo(tx.getGatewayReference());
        });
    }

    @Test
    void partialRefund_leavesTheTransactionPartiallyRefunded() throws Exception {
        UUID booking = UUID.randomUUID();
        captured(booking, "100.00");

        mvc.perform(refund(booking, "40.50", complaintKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(40.50))
                .andExpect(jsonPath("$.transactionStatus").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.transactionRefundedAmount").value(40.50));
    }

    /** Earlier FAILED attempts of the booking are skipped; the captured attempt is refunded. */
    @Test
    void refundsTheCapturedAttempt_notAnEarlierFailedOne() throws Exception {
        UUID booking = UUID.randomUUID();
        payment(booking, "100.00", TransactionStatus.FAILED);
        PaymentTransaction paid = captured(booking, "100.00");

        mvc.perform(refund(booking, "25.00", complaintKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(paid.getId().toString()));
    }

    // ----- idempotency -----

    @Test
    void retryWithTheSameKey_replaysTheRefundWithoutRefundingAgain() throws Exception {
        UUID booking = UUID.randomUUID();
        PaymentTransaction tx = captured(booking, "100.00");
        String key = complaintKey();

        String first = body(mvc.perform(refund(booking, "40.00", key)).andExpect(status().isOk()).andReturn());
        String second = body(mvc.perform(refund(booking, "40.00", key)).andExpect(status().isOk()).andReturn());

        assertThat(second).isEqualTo(first);
        assertThat(gateway.refunds).hasSize(1);
        assertThat(refunds.findAll()).hasSize(1);
        assertThat(tx.getRefundedAmount()).isEqualByComparingTo("40.00");
    }

    /** A full refund leaves the payment REFUNDED; the retry must still find it and replay. */
    @Test
    void retryOfAFullRefund_isReplayedEvenThoughThePaymentIsNowRefunded() throws Exception {
        UUID booking = UUID.randomUUID();
        captured(booking, "100.00");
        String key = complaintKey();
        mvc.perform(refund(booking, "100.00", key)).andExpect(status().isOk());

        mvc.perform(refund(booking, "100.00", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.transactionStatus").value("REFUNDED"));
        assertThat(gateway.refunds).hasSize(1);
    }

    @Test
    void sameKeyWithADifferentAmount_isRefused() throws Exception {
        UUID booking = UUID.randomUUID();
        captured(booking, "100.00");
        String key = complaintKey();
        mvc.perform(refund(booking, "40.00", key)).andExpect(status().isOk());

        mvc.perform(refund(booking, "50.00", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(gateway.refunds).hasSize(1);
    }

    /**
     * The gateway did not answer: the refund stays PENDING and the caller is told the outcome is
     * unknown; the same key then re-sends it under the same refund id and records the result.
     */
    @Test
    void unknownGatewayOutcome_isA502_andTheSameKeyResendsUnderTheSameRefundId() throws Exception {
        UUID booking = UUID.randomUUID();
        PaymentTransaction tx = captured(booking, "100.00");
        String key = complaintKey();
        gateway.refundError = new RuntimeException("read timed out");

        mvc.perform(refund(booking, "30.00", key))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("REFUND_OUTCOME_UNKNOWN"));
        assertThat(refunds.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(RefundStatus.PENDING));
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);

        gateway.refundError = null;
        mvc.perform(refund(booking, "30.00", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.refundId").value(refunds.findAll().get(0).getId().toString()));

        PaymentRefund refund = refunds.findAll().get(0);
        assertThat(gateway.refunds).hasSize(2)
                .extracting(GatewayRefundRequest::idempotencyKey)
                .containsOnly(refund.getId().toString());
        assertThat(tx.getRefundedAmount()).isEqualByComparingTo("30.00");
    }

    // ----- refusals: nothing moves -----

    @Test
    void gatewayDecline_isA502RefundGatewayError() throws Exception {
        UUID booking = UUID.randomUUID();
        PaymentTransaction tx = captured(booking, "100.00");
        gateway.refundSucceeds = false;

        mvc.perform(refund(booking, "30.00", complaintKey()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("REFUND_GATEWAY_ERROR"));
        assertThat(refunds.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(RefundStatus.FAILED));
        assertThat(tx.getRefundedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void amountAboveWhatWasPaid_isRefusedBeforeTheGatewayIsCalled() throws Exception {
        UUID booking = UUID.randomUUID();
        captured(booking, "100.00");

        mvc.perform(refund(booking, "150.00", complaintKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        assertThat(gateway.refunds).isEmpty();
        assertThat(refunds.findAll()).isEmpty();
    }

    @Test
    void alreadyFullyRefundedUnderAnotherKey_isA409() throws Exception {
        UUID booking = UUID.randomUUID();
        captured(booking, "100.00");
        mvc.perform(refund(booking, "100.00", complaintKey())).andExpect(status().isOk());

        mvc.perform(refund(booking, "10.00", complaintKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
        assertThat(gateway.refunds).hasSize(1);
    }

    @Test
    void bookingWithNoPayment_is404() throws Exception {
        mvc.perform(refund(UUID.randomUUID(), "10.00", complaintKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void bookingWhosePaymentWasNeverCaptured_is409() throws Exception {
        UUID booking = UUID.randomUUID();
        payment(booking, "100.00", TransactionStatus.FAILED);
        payment(booking, "100.00", TransactionStatus.PENDING);

        mvc.perform(refund(booking, "10.00", complaintKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_NOT_REFUNDABLE"));
        assertThat(gateway.refunds).isEmpty();
    }

    /** Two captured charges for one booking: refuse rather than guess which one to refund. */
    @Test
    void bookingWithTwoCapturedPayments_isRefusedAsAmbiguous() throws Exception {
        UUID booking = UUID.randomUUID();
        captured(booking, "100.00");
        captured(booking, "100.00");

        mvc.perform(refund(booking, "10.00", complaintKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AMBIGUOUS_BOOKING_PAYMENT"));
        assertThat(gateway.refunds).isEmpty();
    }

    @Test
    void missingIdempotencyKeyOrBooking_is400() throws Exception {
        mvc.perform(post("/internal/payments/refunds")
                        .header(InternalApiKeyFilter.HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"" + UUID.randomUUID() + "\",\"amount\":10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        mvc.perform(post("/internal/payments/refunds")
                        .header(InternalApiKeyFilter.HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"idempotencyKey\":\"k\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ----- ownership: the internal key is the only way in -----

    @Test
    void withoutTheInternalKey_isRefusedAndRefundsNothing() throws Exception {
        UUID booking = UUID.randomUUID();
        PaymentTransaction tx = captured(booking, "100.00");
        String body = "{\"bookingId\":\"" + booking + "\",\"amount\":100.00,\"idempotencyKey\":\"k-1\"}";

        mvc.perform(post("/internal/payments/refunds")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));
        mvc.perform(post("/internal/payments/refunds")
                        .header(InternalApiKeyFilter.HEADER, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(gateway.refunds).isEmpty();
        assertThat(refunds.findAll()).isEmpty();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** Records refund requests; answers with a reference derived from the refund id, or as configured. */
    private static final class RecordingGateway implements PaymentGatewayPort {
        final List<GatewayRefundRequest> refunds = new ArrayList<>();
        RuntimeException refundError;
        boolean refundSucceeds = true;

        @Override
        public String gatewayId() {
            return GATEWAY;
        }

        @Override
        public GatewayChargeResult charge(GatewayChargeRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean verifyCallbackSignature(String payload, String signature) {
            return false;
        }

        @Override
        public GatewayRefundResult refund(GatewayRefundRequest request) {
            refunds.add(request);
            if (refundError != null) {
                throw refundError;
            }
            return refundSucceeds
                    ? new GatewayRefundResult("rfnd_" + request.idempotencyKey(), true)
                    : new GatewayRefundResult(null, false);
        }

        @Override
        public GatewayTransferResult transfer(GatewayTransferRequest request) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
