package com.jihajoor.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests for the shared-credential guard on the Payment Service's {@code /internal/**}, which today
 * is the money-moving {@code POST /internal/payments/refunds}. The cases that matter are that it
 * never falls open when unconfigured, guards encoded spellings of the prefix, and leaves every
 * other path (customer payments, staff refunds, the public gateway callbacks) alone.
 */
class InternalApiKeyFilterTest {

    private static final String KEY = "correct-internal-key";
    private static final String REFUNDS = "/internal/payments/refunds";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest internalRequest(String presentedKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", REFUNDS);
        if (presentedKey != null) {
            request.addHeader(InternalApiKeyFilter.HEADER, presentedKey);
        }
        return request;
    }

    @Test
    void correctKey_authenticatesTheCallingServiceAndContinues() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(internalRequest(KEY), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void wrongKey_isRejectedAndTheChainIsNotInvoked() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(internalRequest("not-the-key"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INTERNAL_AUTH_FAILED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingHeader_isRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(internalRequest(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    /** An unset key must close the refund endpoint, not open it to anyone. */
    @Test
    void unconfiguredKey_rejectsEveryInternalRequestRatherThanFallingOpen() throws Exception {
        for (String configured : new String[] {null, "", "   "}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            new InternalApiKeyFilter(configured).doFilter(internalRequest(KEY), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    /** Spring MVC decodes {@code /%69nternal/...} before routing it, so that spelling is guarded too. */
    @Test
    void encodedOrUpperCaseInternalPath_isStillGuarded() throws Exception {
        for (String uri : new String[] {"/%69nternal/payments/refunds", "/INTERNAL/payments/refunds"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            new InternalApiKeyFilter(KEY).doFilter(request, response, chain);

            assertThat(response.getStatus()).as(uri).isEqualTo(401);
            assertThat(chain.getRequest()).as(uri).isNull();
        }
    }

    @Test
    void nonInternalPaths_areLeftAlone() throws Exception {
        for (String uri : new String[] {"/payments/123/refunds", "/payments/callbacks/123",
                "/admin/payments/123/refund"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            // No key configured and none presented: these paths must still reach the JWT filter
            // (or, for callbacks, the signature check) rather than being rejected here.
            new InternalApiKeyFilter(null).doFilter(request, response, chain);

            assertThat(response.getStatus()).as(uri).isEqualTo(200);
            assertThat(chain.getRequest()).as(uri).isNotNull();
        }
    }
}
