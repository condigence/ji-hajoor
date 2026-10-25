package com.jihajoor.payment.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.payment.api.dto.InternalRefundRequest;
import com.jihajoor.payment.api.dto.InternalRefundResponse;
import com.jihajoor.payment.service.BookingRefundService;

import jakarta.validation.Valid;

/**
 * Service-to-service refund of a booking's payment (Requirement 12.7, 16.5): the Complaint Service
 * calls it when a Support_Agent approves a refund on a complaint, which carries the booking id but
 * not the payment transaction.
 *
 * <p>Lives on the {@code /internal/**} surface: it is not routed through the API Gateway, carries
 * no end-user token, and is authenticated by the shared {@code X-Internal-Api-Key} that
 * {@code InternalApiKeyFilter} checks; the security chain admits it only with the
 * {@code ROLE_INTERNAL} authority that filter grants, so no user token, staff or otherwise, can
 * reach it. There is deliberately no RBAC rule for it in {@code PaymentRbacConfig}, as in the other
 * services: the caller is a service, and whether the person behind it may refund was decided by the
 * calling service (the Complaint Service admits only Support_Agent tiers to its refund endpoint).
 *
 * <h2>Contract</h2>
 * {@code POST /internal/payments/refunds} with {@link InternalRefundRequest}
 * {@code {bookingId, amount, reason, idempotencyKey}}:
 * <ul>
 *   <li>{@code 200} {@link InternalRefundResponse}: the refund executed (or had already executed
 *       under this key, replayed without calling the gateway again).</li>
 *   <li>{@code 400 VALIDATION_ERROR}: the body is invalid, or the amount exceeds what is still
 *       unrefunded. Nothing moved.</li>
 *   <li>{@code 404 PAYMENT_NOT_FOUND}: no payment was ever taken for the booking.</li>
 *   <li>{@code 409 PAYMENT_NOT_REFUNDABLE} (no captured payment), {@code INVALID_STATE_TRANSITION}
 *       (already fully refunded under another key), {@code GATEWAY_REFERENCE_MISSING},
 *       {@code REFUND_IN_PROGRESS} (another refund of the payment is PENDING),
 *       {@code AMBIGUOUS_BOOKING_PAYMENT}: refused before the gateway was called; nothing moved.</li>
 *   <li>{@code 409 IDEMPOTENCY_KEY_REUSED}: the key was already used for another amount.</li>
 *   <li>{@code 502 REFUND_GATEWAY_ERROR}: the gateway explicitly declined (this time or, on a
 *       replay, the first time); nothing moved.</li>
 *   <li>{@code 502 REFUND_OUTCOME_UNKNOWN}: the gateway did not answer; the refund stays PENDING
 *       and must be retried with the <em>same</em> key (or reconciled by Finance_Admin).</li>
 *   <li>{@code 401 INTERNAL_AUTH_FAILED}: the internal key is missing, wrong or not configured.</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/payments")
public class InternalRefundController {

    private final BookingRefundService bookingRefundService;

    public InternalRefundController(BookingRefundService bookingRefundService) {
        this.bookingRefundService = bookingRefundService;
    }

    @PostMapping("/refunds")
    public InternalRefundResponse refund(@Valid @RequestBody InternalRefundRequest request) {
        return InternalRefundResponse.from(bookingRefundService.refundBooking(request.bookingId(),
                request.amount(), request.idempotencyKey(), request.reason()));
    }
}
