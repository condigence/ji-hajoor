package com.jihajoor.customer.booking;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound from the {@code jihajoor.booking.*} configuration namespace.
 */
@Component
@ConfigurationProperties(prefix = "jihajoor.booking")
public class BookingClientProperties {

    /** Base URL of the Booking Service. */
    private String baseUrl = "http://booking-service:8084";

    /**
     * Connect and read timeout for the active-booking check. Without one a hung Booking Service
     * holds the customer's address-delete request (and its servlet thread) indefinitely.
     */
    private Duration timeout = Duration.ofSeconds(3);

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
