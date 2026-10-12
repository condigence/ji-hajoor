package com.jihajoor.auth.sms;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link SmsGatewayPort} adapter for MSG91 (India). Sends sign-in codes through MSG91's OTP API
 * ({@code POST /api/v5/otp}) using a DLT-approved OTP template: we generate and verify the code
 * ourselves and only ask MSG91 to deliver it, so the OTP business logic is unchanged.
 *
 * <p>Activated by {@code jihajoor.sms.provider=msg91}. Requires the account's auth key and the
 * OTP template id; both are checked at start-up so a half-configured deployment fails fast
 * instead of failing every customer sign-in.
 *
 * <p>MSG91 reports some errors with HTTP 200, so success is read from the response body
 * ({@code "type":"success"}), not the status code. Neither the auth key nor the code is logged.
 */
@Component
@ConditionalOnProperty(prefix = "jihajoor.sms", name = "provider", havingValue = "msg91")
public class Msg91SmsGatewayAdapter implements SmsGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(Msg91SmsGatewayAdapter.class);

    private final RestClient client;
    private final String authKey;
    private final String templateId;

    @Autowired
    public Msg91SmsGatewayAdapter(@Value("${jihajoor.sms.msg91.base-url:https://control.msg91.com}") String baseUrl,
                                  @Value("${jihajoor.sms.msg91.auth-key:}") String authKey,
                                  @Value("${jihajoor.sms.msg91.otp-template-id:}") String templateId) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts()).build(), authKey, templateId);
    }

    Msg91SmsGatewayAdapter(RestClient client, String authKey, String templateId) {
        this.authKey = authKey == null ? "" : authKey.trim();
        this.templateId = templateId == null ? "" : templateId.trim();
        if (this.authKey.isEmpty() || this.templateId.isEmpty()) {
            throw new IllegalStateException("SMS_PROVIDER=msg91 needs MSG91_AUTH_KEY and "
                    + "MSG91_OTP_TEMPLATE_ID. Set both, or set SMS_PROVIDER=log until the DLT "
                    + "template is approved.");
        }
        this.client = client;
        log.info("SMS gateway: MSG91 (OTP template {})", this.templateId);
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        return factory;
    }

    @Override
    public void sendOtp(String mobileNumber, String code, Duration validity) throws SmsDeliveryException {
        // MSG91 wants the country code without the leading '+': +919876543210 -> 919876543210.
        String mobile = mobileNumber == null ? "" : mobileNumber.replaceAll("\\D", "");
        long expiryMinutes = Math.max(1, validity.toMinutes());

        String body;
        try {
            body = client.post()
                    .uri(uri -> uri.path("/api/v5/otp")
                            .queryParam("template_id", templateId)
                            .queryParam("mobile", mobile)
                            .queryParam("otp", code)
                            .queryParam("otp_length", code.length())
                            .queryParam("otp_expiry", expiryMinutes)
                            .queryParam("realTimeResponse", 1)
                            .build())
                    .header("authkey", authKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException ex) {
            String detail = describe(ex.getResponseBodyAsString());
            log.warn("MSG91 rejected OTP send: HTTP {} {}", ex.getStatusCode().value(), detail);
            throw new SmsDeliveryException("SMS gateway rejected the message: " + detail, ex);
        } catch (RestClientException ex) {
            log.warn("MSG91 unreachable: {}", ex.getMessage());
            throw new SmsDeliveryException("SMS gateway unavailable", ex);
        }

        if (!"success".equalsIgnoreCase(field(body, "type"))) {
            String detail = describe(body);
            log.warn("MSG91 did not accept OTP send: {}", detail);
            throw new SmsDeliveryException("SMS gateway rejected the message: " + detail);
        }
        String requestId = field(body, "request_id");
        log.info("SMS dispatch accepted by MSG91 (request {})", requestId == null ? "?" : requestId);
    }

    /**
     * Free-text SMS is not used by the platform; DLT rules only allow pre-registered templates,
     * so everything goes through {@link #sendOtp}.
     */
    @Override
    public void send(String mobileNumber, String message) throws SmsDeliveryException {
        throw new SmsDeliveryException("MSG91 gateway only sends DLT-approved OTP templates");
    }

    /**
     * One top-level string field of MSG91's small JSON reply ({@code type}, {@code message},
     * {@code request_id}); null when absent. A full JSON parser is not needed for three flat keys.
     */
    static String field(String body, String name) {
        if (body == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** The provider's error message, never the request (which carries the key and code). */
    private String describe(String body) {
        String message = field(body, "message");
        if (message != null) {
            return message;
        }
        if (body == null || body.isBlank()) {
            return "empty response";
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
