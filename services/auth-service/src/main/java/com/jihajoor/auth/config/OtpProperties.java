package com.jihajoor.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the OTP registration flow.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * jihajoor:
 *   auth:
 *     otp:
 *       ttl: 5m                 # OTP validity window (Requirement 1.2, 1.4)
 *       length: 6               # number of digits
 *       max-verify-attempts: 5  # consecutive wrong attempts before lockout (Requirement 1.3)
 *       lockout: 30m            # lockout window after max attempts (Requirement 1.3, Property 25)
 *       max-requests-per-hour: 5 # per-phone OTP request cap (Requirement 23.4)
 *       rate-limit-window: 1h
 * </pre>
 */
@ConfigurationProperties(prefix = "jihajoor.auth.otp")
public class OtpProperties {

    /** OTP validity window. Default 5 minutes (Requirement 1.2, 1.4). */
    private Duration ttl = Duration.ofMinutes(5);

    /** Number of numeric digits in a generated OTP. */
    private int length = 6;

    /** Consecutive incorrect submissions permitted before lockout (Requirement 1.3). */
    private int maxVerifyAttempts = 5;

    /** Duration a session is locked after reaching {@link #maxVerifyAttempts} (Property 25). */
    private Duration lockout = Duration.ofMinutes(30);

    /** Maximum OTP requests allowed per phone within {@link #rateLimitWindow} (Requirement 23.4). */
    private int maxRequestsPerWindow = 5;

    /** Rate-limit sliding window for OTP requests. Default 1 hour (Requirement 23.4). */
    private Duration rateLimitWindow = Duration.ofHours(1);

    /**
     * TEMPORARY TEST MODE. When set, every OTP is this code and no SMS is sent, so sign-in works
     * while the SMS gateway is unavailable. Accounts holding a staff role are refused in this mode
     * (they sign in with username and password). Leave unset in normal operation.
     */
    private String fixedCode;

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getMaxVerifyAttempts() {
        return maxVerifyAttempts;
    }

    public void setMaxVerifyAttempts(int maxVerifyAttempts) {
        this.maxVerifyAttempts = maxVerifyAttempts;
    }

    public Duration getLockout() {
        return lockout;
    }

    public void setLockout(Duration lockout) {
        this.lockout = lockout;
    }

    public int getMaxRequestsPerWindow() {
        return maxRequestsPerWindow;
    }

    public void setMaxRequestsPerWindow(int maxRequestsPerWindow) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
    }

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public void setRateLimitWindow(Duration rateLimitWindow) {
        this.rateLimitWindow = rateLimitWindow;
    }

    public String getFixedCode() {
        return fixedCode;
    }

    public void setFixedCode(String fixedCode) {
        this.fixedCode = fixedCode;
    }

    /** The fixed test code, or null when test mode is off or the value is not numeric. */
    public String activeFixedCode() {
        if (fixedCode == null || fixedCode.isBlank()) {
            return null;
        }
        String code = fixedCode.trim();
        return code.matches("\\d{4,10}") ? code : null;
    }
}
