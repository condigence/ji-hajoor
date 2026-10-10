package com.jihajoor.auth.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link Msg91SmsGatewayAdapter}: the OTP request MSG91 receives, and how its
 * success and error answers (including errors reported with HTTP 200) are handled.
 */
class Msg91SmsGatewayAdapterTest {

    private static final String BASE = "https://msg91.test";

    private MockRestServiceServer server;
    private Msg91SmsGatewayAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new Msg91SmsGatewayAdapter(builder.build(), "test-key", "tmpl-123");
    }

    @Test
    void sendsOtpThroughTemplate_withCodeExpiryAndKeyHeader() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v5/otp?")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("authkey", "test-key"))
                .andExpect(queryParam("template_id", "tmpl-123"))
                .andExpect(queryParam("mobile", "919876543210"))
                .andExpect(queryParam("otp", "482913"))
                .andExpect(queryParam("otp_length", "6"))
                .andExpect(queryParam("otp_expiry", "5"))
                .andRespond(withSuccess("{\"type\":\"success\",\"request_id\":\"abc\"}",
                        MediaType.APPLICATION_JSON));

        assertThatCode(() -> adapter.sendOtp("+919876543210", "482913", Duration.ofMinutes(5)))
                .doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void errorReportedWithHttp200_isADeliveryFailure() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v5/otp?")))
                .andRespond(withSuccess("{\"type\":\"error\",\"message\":\"Invalid template id\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.sendOtp("+919876543210", "482913", Duration.ofMinutes(5)))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("Invalid template id");
    }

    @Test
    void httpError_isADeliveryFailure_withoutLeakingTheKey() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v5/otp?")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"error\",\"message\":\"Auth Key missing\"}"));

        assertThatThrownBy(() -> adapter.sendOtp("+919876543210", "482913", Duration.ofMinutes(5)))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("Auth Key missing")
                .hasMessageNotContaining("test-key");
    }

    @Test
    void refusesToStart_withoutKeyOrTemplate() {
        RestClient client = RestClient.builder().build();
        assertThatThrownBy(() -> new Msg91SmsGatewayAdapter(client, "", "tmpl-123"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Msg91SmsGatewayAdapter(client, "key", " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void freeTextSms_isRefused() {
        assertThatThrownBy(() -> adapter.send("+919876543210", "hello"))
                .isInstanceOf(SmsDeliveryException.class);
    }

    @Test
    void defaultOtpText_matchesTheDltTemplate() {
        assertThat(SmsGatewayPort.otpMessage("482913", Duration.ofMinutes(5)))
                .isEqualTo("Your Ji-Hajoor verification code is 482913. It expires in 5 minutes.");
    }
}
