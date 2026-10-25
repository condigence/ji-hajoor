package com.jihajoor.complaint.payment;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jihajoor.complaint.config.PaymentClientProperties;

/**
 * Production {@link RefundPort}: refunds the complaint's booking through the Payment Service's
 * service-to-service {@code POST /internal/payments/refunds}, presenting the shared
 * {@code X-Internal-Api-Key} ({@code INTERNAL_API_KEY}) (Requirement 16.5, 16.6). The Payment
 * Service resolves the booking's captured payment and runs its ordinary refund flow, so the
 * over-refund checks, the PENDING-before-the-gateway reservation and the gateway idempotency all
 * apply. Selected by {@code jihajoor.complaint.payment.client=http}, the default.
 *
 * <h2>Idempotency</h2>
 * The request carries the complaint refund's stable key ({@code complaint-refund:<refund id>}), so
 * however often this refund is sent (a retry after a lost answer, or two attempts that overlap) the
 * Payment Service executes it at most once: a refund that SUCCEEDED is replayed, a PENDING one is
 * re-sent to the gateway under its original gateway refund id, which the gateway de-duplicates.
 *
 * <h2>Outcomes</h2>
 * <ul>
 *   <li><strong>200</strong> with an executed ({@code SUCCEEDED}) refund: {@link RefundResult#approved}
 *       with the Payment Service refund id as the reference.</li>
 *   <li><strong>A refusal in which no money moved</strong>, recognised by its error code
 *       ({@link #REJECTION_CODES}): 400 {@code VALIDATION_ERROR} (for example more than was paid),
 *       404 {@code PAYMENT_NOT_FOUND}, 409 {@code PAYMENT_NOT_REFUNDABLE},
 *       {@code INVALID_STATE_TRANSITION} (already fully refunded), {@code GATEWAY_REFERENCE_MISSING},
 *       {@code REFUND_IN_PROGRESS} (another refund of the payment is in flight),
 *       {@code AMBIGUOUS_BOOKING_PAYMENT}, and 502 {@code REFUND_GATEWAY_ERROR} (the gateway
 *       declined): {@link RefundResult#rejected} with the code and message, so the complaint goes to
 *       REFUND_FAILED and Finance_Admin is alerted, as for any rejection (16.6).</li>
 *   <li><strong>Everything else</strong> throws {@link RefundOutcomeUnknownException}: no
 *       connection, a timeout, a 5xx, 502 {@code REFUND_OUTCOME_UNKNOWN} (the gateway did not
 *       confirm; the Payment Service keeps its refund PENDING), 409 {@code IDEMPOTENCY_KEY_REUSED}
 *       (a refund under this key exists for another amount, which this service never sends), a
 *       refused credential (401/403), a 404 without {@code PAYMENT_NOT_FOUND} (a wrong base URL or a
 *       Payment Service without the endpoint), a 200 without an executed refund, or an unreadable
 *       body. None of these may be reported as a success, and none as a rejection either: money may
 *       have moved, or the request may simply not have been understood, and recording FAILED would
 *       tell Finance_Admin the customer was not refunded. The complaint refund stays PENDING and the
 *       Support_Agent retries with the same idempotency key once the cause is fixed.</li>
 * </ul>
 *
 * <h2>Why no retry or circuit breaker</h2>
 * This is a money-moving POST on a Support_Agent's request, not a read on a hot path. Retrying in
 * line would overlap attempts of the same refund at the gateway for no gain, and the caller already
 * turns an unknown outcome into an explicit, safely retryable state. The call is bounded instead by
 * connect and read timeouts ({@link PaymentClientProperties}), as the Payment Service's own wallet
 * client is.
 */
@Component
@ConditionalOnProperty(name = "jihajoor.complaint.payment.client", havingValue = "http", matchIfMissing = true)
public class HttpRefundAdapter implements RefundPort {

    private static final Logger log = LoggerFactory.getLogger(HttpRefundAdapter.class);

    /** Header carrying the shared service credential the Payment Service expects on /internal/**. */
    static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    static final String REFUNDS_PATH = "/internal/payments/refunds";

    /** Status of an executed refund in the Payment Service's answer. */
    static final String SUCCEEDED = "SUCCEEDED";

    /**
     * Error codes with which the Payment Service refuses a refund <em>before</em> any money moved
     * (or reports that the gateway explicitly declined it). Only these become a rejection.
     */
    static final Set<String> REJECTION_CODES = Set.of(
            "VALIDATION_ERROR",
            "PAYMENT_NOT_FOUND",
            "TRANSACTION_NOT_FOUND",
            "PAYMENT_NOT_REFUNDABLE",
            "INVALID_STATE_TRANSITION",
            "GATEWAY_REFERENCE_MISSING",
            "REFUND_IN_PROGRESS",
            "AMBIGUOUS_BOOKING_PAYMENT",
            "REFUND_GATEWAY_ERROR");

    private static final ParameterizedTypeReference<Map<String, Object>> ERROR_BODY =
            new ParameterizedTypeReference<>() { };

    /** Null when no credential is configured; every refund then ends with its outcome unknown. */
    private final RestClient restClient;

    public HttpRefundAdapter(PaymentClientProperties properties) {
        String key = properties.getInternalApiKey();
        if (key == null || key.isBlank()) {
            // Not fatal at startup: the rest of the Complaint Service works without it.
            log.error("jihajoor.complaint.payment.internal-api-key (INTERNAL_API_KEY) is not configured; "
                    + "no complaint refund can be sent to the Payment Service");
            this.restClient = null;
            return;
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(INTERNAL_KEY_HEADER, key)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount,
                                      String idempotencyKey) {
        if (restClient == null) {
            throw new RefundOutcomeUnknownException("The Payment Service credential "
                    + "(INTERNAL_API_KEY) is not configured; the refund was not sent");
        }
        RefundRequestBody body = new RefundRequestBody(bookingId, amount,
                "Complaint " + complaintId, idempotencyKey);
        try {
            return restClient.post()
                    .uri(REFUNDS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> interpret(response, complaintId, idempotencyKey));
        } catch (RefundOutcomeUnknownException e) {
            throw e;
        } catch (RuntimeException e) {
            // Connection refused, a timeout, an unreadable body. Never log the credential; the
            // exception text does not carry it.
            log.warn("Refund call for complaint {} (key {}) failed with no answer: {}", complaintId,
                    idempotencyKey, e.toString());
            throw new RefundOutcomeUnknownException("The Payment Service did not answer the refund "
                    + "request: " + e.getMessage(), e);
        }
    }

    private static RefundResult interpret(ConvertibleClientHttpResponse response, UUID complaintId,
                                          String idempotencyKey) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        int code = status.value();
        if (status.is2xxSuccessful()) {
            RefundResponseBody refund = response.bodyTo(RefundResponseBody.class);
            if (refund == null || refund.refundId() == null || !SUCCEEDED.equals(refund.status())) {
                throw new RefundOutcomeUnknownException("The Payment Service answered HTTP " + code
                        + " without an executed refund (status "
                        + (refund == null ? null : refund.status()) + ")");
            }
            log.info("Refund for complaint {} (key {}) executed by the Payment Service: refund {}",
                    complaintId, idempotencyKey, refund.refundId());
            return RefundResult.approved(refund.refundId().toString());
        }

        Map<String, Object> error = readError(response);
        String errorCode = text(error, "errorCode");
        String message = text(error, "message");
        if ((status.is4xxClientError() || code == 502) && errorCode != null
                && REJECTION_CODES.contains(errorCode)) {
            log.warn("Payment Service refused the refund for complaint {} (key {}): HTTP {} {}",
                    complaintId, idempotencyKey, code, errorCode);
            return RefundResult.rejected("Payment Service refused the refund (" + code + " "
                    + errorCode + ")" + (message == null ? "" : ": " + message));
        }
        if (code == 401 || code == 403) {
            log.error("Payment Service refused this service's credential with HTTP {}; check that "
                    + "INTERNAL_API_KEY matches across services", code);
        }
        throw new RefundOutcomeUnknownException("The Payment Service did not confirm the refund (HTTP "
                + code + (errorCode == null ? "" : " " + errorCode) + ")"
                + (message == null ? "" : ": " + message));
    }

    /** The shared error envelope, or an empty map when the body is missing or not JSON. */
    private static Map<String, Object> readError(ConvertibleClientHttpResponse response) {
        try {
            Map<String, Object> body = response.bodyTo(ERROR_BODY);
            return body == null ? Map.of() : body;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** Body of {@code POST /internal/payments/refunds}. */
    record RefundRequestBody(UUID bookingId, BigDecimal amount, String reason, String idempotencyKey) {
    }

    /** The subset of the Payment Service's refund answer this service reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RefundResponseBody(UUID refundId, UUID transactionId, String status) {
    }
}
