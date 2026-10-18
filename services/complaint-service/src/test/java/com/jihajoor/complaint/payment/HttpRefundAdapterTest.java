package com.jihajoor.complaint.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.jihajoor.complaint.config.PaymentClientProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests {@link HttpRefundAdapter} against a loopback stub of the Payment Service's
 * {@code POST /internal/payments/refunds} (Requirement 16.5, 16.6): the request it sends (path,
 * service credential, booking, amount and the stable idempotency key) and how each answer is
 * mapped. The mapping is the point: only an executed refund may become a success, only a refusal
 * in which no money moved may become a rejection (and so REFUND_FAILED), and everything else must
 * surface as {@link RefundOutcomeUnknownException} so the complaint refund stays PENDING and can be
 * re-sent under the same key.
 */
class HttpRefundAdapterTest {

    private static final String KEY = "test-internal-key";
    private static final UUID BOOKING = UUID.fromString("b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55");
    private static final UUID COMPLAINT = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");
    private static final String IDEMPOTENCY_KEY = "complaint-refund:7d4e6a8b-0c55-4c98-9f12-b2e7d4103a65";
    private static final BigDecimal AMOUNT = new BigDecimal("1250.50");

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestPaths = new CopyOnWriteArrayList<>();
    private final List<String> requestMethods = new CopyOnWriteArrayList<>();
    private final List<String> presentedKeys = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "{}";
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPaths.add(exchange.getRequestURI().getRawPath());
            requestMethods.add(exchange.getRequestMethod());
            String key = exchange.getRequestHeaders().getFirst(HttpRefundAdapter.INTERNAL_KEY_HEADER);
            presentedKeys.add(key == null ? "<none>" : key);
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusToReturn, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpRefundAdapter adapter() {
        return adapter(p -> { });
    }

    private HttpRefundAdapter adapter(Consumer<PaymentClientProperties> tweak) {
        PaymentClientProperties properties = new PaymentClientProperties();
        properties.setBaseUrl(baseUrl);
        properties.setInternalApiKey(KEY);
        properties.setConnectTimeout(Duration.ofMillis(500));
        properties.setReadTimeout(Duration.ofMillis(500));
        tweak.accept(properties);
        return new HttpRefundAdapter(properties);
    }

    private RefundResult send() {
        return adapter().requestRefund(BOOKING, COMPLAINT, AMOUNT, IDEMPOTENCY_KEY);
    }

    private void answer(int status, String body) {
        statusToReturn = status;
        bodyToReturn = body;
    }

    private static String error(String errorCode, String message) {
        return "{\"errorCode\":\"" + errorCode + "\",\"message\":\"" + message
                + "\",\"correlationId\":\"corr-1\",\"details\":[]}";
    }

    // ---- Executed refund -------------------------------------------------------------------------

    @Test
    void executedRefund_isApprovedWithThePaymentRefundId_andTheRequestCarriesKeyBookingAndAmount() {
        UUID refundId = UUID.randomUUID();
        answer(200, "{\"refundId\":\"" + refundId + "\",\"transactionId\":\"" + UUID.randomUUID()
                + "\",\"bookingId\":\"" + BOOKING + "\",\"amount\":1250.50,\"status\":\"SUCCEEDED\","
                + "\"gatewayRefundReference\":\"rfnd_1\",\"transactionStatus\":\"PARTIALLY_REFUNDED\","
                + "\"transactionRefundedAmount\":1250.50}");

        RefundResult result = send();

        assertThat(result.approved()).isTrue();
        assertThat(result.transactionRef()).isEqualTo(refundId.toString());
        assertThat(requestMethods).containsExactly("POST");
        assertThat(requestPaths).containsExactly("/internal/payments/refunds");
        assertThat(presentedKeys).containsExactly(KEY);
        assertThat(requestBodies).singleElement().asString()
                .contains("\"bookingId\":\"" + BOOKING + "\"")
                .contains("\"amount\":1250.50")
                .contains("\"idempotencyKey\":\"" + IDEMPOTENCY_KEY + "\"")
                .contains("\"reason\":\"Complaint " + COMPLAINT + "\"");
    }

    // ---- Refusals in which no money moved: rejected ----------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "400, VALIDATION_ERROR",
            "404, PAYMENT_NOT_FOUND",
            "409, PAYMENT_NOT_REFUNDABLE",
            "409, INVALID_STATE_TRANSITION",
            "409, GATEWAY_REFERENCE_MISSING",
            "409, REFUND_IN_PROGRESS",
            "409, AMBIGUOUS_BOOKING_PAYMENT",
            "502, REFUND_GATEWAY_ERROR"})
    void businessRefusal_isARejectionCarryingTheCodeAndMessage(int status, String errorCode) {
        answer(status, error(errorCode, "refused for a reason"));

        RefundResult result = send();

        assertThat(result.approved()).isFalse();
        assertThat(result.transactionRef()).isNull();
        assertThat(result.failureReason()).contains(String.valueOf(status)).contains(errorCode)
                .contains("refused for a reason");
    }

    // ---- No definitive answer: outcome unknown, never success or rejection ------------------------

    @ParameterizedTest
    @CsvSource({
            // The gateway did not confirm; the Payment Service keeps its refund PENDING.
            "502, REFUND_OUTCOME_UNKNOWN",
            // A refund under this key exists for another amount: money may have moved.
            "409, IDEMPOTENCY_KEY_REUSED",
            "500, INTERNAL_ERROR",
            "503, SERVICE_UNAVAILABLE",
            // The credential was refused: nothing ran, but it is an operator fix, then a re-send.
            "401, INTERNAL_AUTH_FAILED",
            "403, FORBIDDEN"})
    void answerWithoutAnOutcome_throwsOutcomeUnknown(int status, String errorCode) {
        answer(status, error(errorCode, "no outcome"));

        Throwable thrown = catchThrowable(this::send);

        assertThat(thrown).isInstanceOf(RefundOutcomeUnknownException.class)
                .hasMessageContaining(String.valueOf(status));
    }

    /** A bare 404 is a wrong base URL or a Payment Service without the endpoint, not "no payment". */
    @Test
    void notFoundWithoutTheErrorCode_isNotReadAsARejection() {
        answer(404, "");

        assertThat(catchThrowable(this::send)).isInstanceOf(RefundOutcomeUnknownException.class);
    }

    @Test
    void successStatusWithoutAnExecutedRefund_isNotASuccess() {
        answer(200, "{\"refundId\":\"" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        assertThat(catchThrowable(this::send)).isInstanceOf(RefundOutcomeUnknownException.class);

        answer(200, "{\"status\":\"SUCCEEDED\"}");
        assertThat(catchThrowable(this::send)).isInstanceOf(RefundOutcomeUnknownException.class);

        answer(200, "not json");
        assertThat(catchThrowable(this::send)).isInstanceOf(RefundOutcomeUnknownException.class);
    }

    @Test
    void timeout_throwsOutcomeUnknown() {
        delayMillis = 1500;
        answer(200, "{}");

        assertThat(catchThrowable(this::send)).isInstanceOf(RefundOutcomeUnknownException.class);
    }

    @Test
    void unreachablePaymentService_throwsOutcomeUnknown() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpRefundAdapter adapter = adapter(p -> p.setBaseUrl("http://127.0.0.1:" + closedPort));

        assertThat(catchThrowable(() -> adapter.requestRefund(BOOKING, COMPLAINT, AMOUNT, IDEMPOTENCY_KEY)))
                .isInstanceOf(RefundOutcomeUnknownException.class);
    }

    @Test
    void missingCredential_sendsNothingAndThrowsOutcomeUnknown() {
        for (String key : new String[] {null, "", "  "}) {
            Throwable thrown = catchThrowable(() -> adapter(p -> p.setInternalApiKey(key))
                    .requestRefund(BOOKING, COMPLAINT, AMOUNT, IDEMPOTENCY_KEY));

            assertThat(thrown).isInstanceOf(RefundOutcomeUnknownException.class)
                    .hasMessageContaining("INTERNAL_API_KEY");
        }
        assertThat(requestPaths).isEmpty();
    }
}
