package com.jihajoor.customer.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** The Booking Service active-address check over a real local HTTP server. */
class HttpBookingClientAdapterTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<Integer> code = new AtomicReference<>(200);
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenQuery = new AtomicReference<>();
    private HttpBookingClientAdapter client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/bookings/active", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst(HttpBookingClientAdapter.INTERNAL_KEY_HEADER));
            seenQuery.set(exchange.getRequestURI().getQuery());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.get(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        BookingClientProperties props = new BookingClientProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setTimeout(Duration.ofSeconds(2));
        client = new HttpBookingClientAdapter(props, "internal-key");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void reportsTheActiveBookingUsingTheAddress_withTheInternalKey() {
        UUID customer = UUID.randomUUID();
        UUID address = UUID.randomUUID();
        body.set("{\"bookingReference\":\"JH-1001\"}");

        assertThat(client.findActiveBookingUsingAddress(customer, address)).contains("JH-1001");
        assertThat(seenKey.get()).isEqualTo("internal-key");
        assertThat(seenQuery.get()).contains("customerId=" + customer).contains("addressId=" + address);
    }

    @Test
    void noReferenceMeansNoActiveBooking() {
        body.set("{\"bookingReference\":\" \"}");
        assertThat(client.findActiveBookingUsingAddress(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
        body.set("{}");
        assertThat(client.findActiveBookingUsingAddress(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void aFailingBookingServiceIsALookupFailure() {
        code.set(503);
        body.set("{\"errorCode\":\"DOWN\"}");
        assertThatThrownBy(() -> client.findActiveBookingUsingAddress(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BookingLookupException.class);
    }

    @Test
    void theDefaultsPointAtTheBookingService() {
        BookingClientProperties defaults = new BookingClientProperties();
        assertThat(defaults.getBaseUrl()).isEqualTo("http://booking-service:8084");
        assertThat(defaults.getTimeout()).isEqualTo(Duration.ofSeconds(3));
    }
}
