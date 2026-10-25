package com.jihajoor.rating.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link HttpProviderRatingAdapter} against a loopback stub of the Provider Service's
 * {@code PUT /internal/providers/{id}/rating}: the request carries the shared credential and the
 * whole aggregate, and each answer is classified as success, a permanent refusal (not re-sent) or a
 * transient failure (stays owed).
 */
class HttpProviderRatingAdapterTest {

    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String KEY = "test-internal-key";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private HttpProviderRatingAdapter adapter;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            keys.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key")));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = ("{\"providerId\":\"" + PROVIDER + "\",\"applied\":true}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        adapter = new HttpProviderRatingAdapter("http://127.0.0.1:" + server.getAddress().getPort(), KEY);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void putsTheWholeAggregateWithTheServiceCredential() {
        adapter.updateAggregateRating(PROVIDER, new ProviderRatingSnapshot(new BigDecimal("4.20"), 12, 7));

        assertThat(requests).containsExactly("PUT /internal/providers/" + PROVIDER + "/rating");
        assertThat(keys).containsExactly(KEY);
        assertThat(bodies).singleElement().satisfies(body -> assertThat(body)
                .contains("\"average\":4.20")
                .contains("\"count\":12")
                .contains("\"sequence\":7"));
    }

    @Test
    void noCountedReviewsIsSentAsANullAverage() {
        adapter.updateAggregateRating(PROVIDER, new ProviderRatingSnapshot(null, 0, 3));

        assertThat(bodies).singleElement().satisfies(body -> assertThat(body)
                .contains("\"average\":null")
                .contains("\"count\":0"));
    }

    @Test
    void anUnknownProviderOrAnInvalidAggregateIsAPermanentRefusal() {
        for (int refused : new int[] {400, 404, 422}) {
            status = refused;
            assertThatThrownBy(() -> adapter.updateAggregateRating(PROVIDER,
                    new ProviderRatingSnapshot(new BigDecimal("4.00"), 1, 1)))
                    .isInstanceOf(ProviderRatingUpdateException.class)
                    .satisfies(e -> assertThat(((ProviderRatingUpdateException) e).isPermanent())
                            .as("HTTP %d", refused).isTrue());
        }
    }

    /** A wrong credential is an operator's fix away, and an outage passes: both stay owed. */
    @Test
    void credentialProblemsAndServerErrorsAreTransient() {
        for (int failing : new int[] {401, 403, 409, 429, 500, 503}) {
            status = failing;
            assertThatThrownBy(() -> adapter.updateAggregateRating(PROVIDER,
                    new ProviderRatingSnapshot(new BigDecimal("4.00"), 1, 1)))
                    .isInstanceOf(ProviderRatingUpdateException.class)
                    .satisfies(e -> assertThat(((ProviderRatingUpdateException) e).isPermanent())
                            .as("HTTP %d", failing).isFalse());
        }
    }

    @Test
    void anUnreachableProviderServiceIsTransient() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpProviderRatingAdapter unreachable =
                new HttpProviderRatingAdapter("http://127.0.0.1:" + closedPort, KEY);

        assertThatThrownBy(() -> unreachable.updateAggregateRating(PROVIDER,
                new ProviderRatingSnapshot(new BigDecimal("4.00"), 1, 1)))
                .isInstanceOf(ProviderRatingUpdateException.class)
                .satisfies(e -> assertThat(((ProviderRatingUpdateException) e).isPermanent()).isFalse());
    }

    @Test
    void permanentRefusalClassification() {
        assertThat(HttpProviderRatingAdapter.isPermanentRefusal(400)).isTrue();
        assertThat(HttpProviderRatingAdapter.isPermanentRefusal(404)).isTrue();
        assertThat(HttpProviderRatingAdapter.isPermanentRefusal(422)).isTrue();
        assertThat(HttpProviderRatingAdapter.isPermanentRefusal(401)).isFalse();
        assertThat(HttpProviderRatingAdapter.isPermanentRefusal(408)).isFalse();
        assertThat(HttpProviderRatingAdapter.isPermanentRefusal(500)).isFalse();
    }
}
