package com.jihajoor.complaint.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Payment Service's {@code POST /internal/payments/refunds}, through
 * which an approved complaint refund moves money (Requirement 16.5). Read by
 * {@code HttpRefundAdapter}; which adapter backs the refund port is chosen separately by
 * {@code jihajoor.complaint.payment.client} ({@code http}, the default, or {@code logging}).
 *
 * <p>The read timeout is deliberately generous. The Payment Service answers only after the gateway
 * has (Razorpay: up to three calls with a 15-second read each), and a call cut short here leaves the
 * refund's outcome unknown, which costs the Support_Agent a retry and Finance_Admin an alert. It
 * must stay well below {@code jihajoor.complaint.refund-attempt-lease}, after which an attempt with
 * no recorded outcome is presumed lost and may be re-sent.
 */
@ConfigurationProperties(prefix = "jihajoor.complaint.payment")
public class PaymentClientProperties {

    /** Base URL of the Payment Service on the internal network. */
    private String baseUrl = "http://payment-service:8088";

    /**
     * Shared service credential presented as {@code X-Internal-Api-Key} on {@code /internal/**}
     * ({@code INTERNAL_API_KEY}). No default: when blank, no refund is sent and every approval ends
     * with its outcome unknown (502 {@code REFUND_OUTCOME_UNKNOWN}) until it is configured.
     */
    private String internalApiKey;

    /** Maximum time to establish the TCP connection. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Maximum time to wait for the Payment Service's answer once connected. */
    private Duration readTimeout = Duration.ofSeconds(60);

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalApiKey() {
        return internalApiKey;
    }

    public void setInternalApiKey(String internalApiKey) {
        this.internalApiKey = internalApiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
