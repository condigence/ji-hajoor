package com.jihajoor.payment.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates service-to-service calls on {@code /internal/**} with a shared secret, the same
 * guard the Booking, Provider and other services put in front of their internal endpoints.
 *
 * <p>The Payment Service's internal surface is the complaint refund endpoint
 * ({@code POST /internal/payments/refunds}), which the Complaint Service calls on behalf of a
 * Support_Agent who has already been authorised there. It carries no end-user token, so neither the
 * JWT filter nor the RBAC filter can authorise it, and it moves money, so it must never be open.
 * The shared credential in the {@code X-Internal-Api-Key} header keeps it off the public surface;
 * the API Gateway does not route {@code /internal/**} either.
 *
 * <p>The key is compared in constant time, never logged, and has no usable default: if
 * {@code jihajoor.payment.internal-api-key} ({@code INTERNAL_API_KEY}) is unset the filter rejects
 * every internal request rather than falling open. It is the same key this service already presents
 * to the Booking and Provider Services.
 *
 * <p>On success the request is authenticated as the calling service with the {@code ROLE_INTERNAL}
 * authority, so the security chain's {@code hasAuthority(ROLE_INTERNAL)} rule is satisfied without
 * inventing a user.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    /** Header carrying the shared internal credential. */
    public static final String HEADER = "X-Internal-Api-Key";

    /** Path prefix this filter guards. */
    public static final String INTERNAL_PREFIX = "/internal/";

    /** Authority granted to an authenticated internal caller. */
    public static final String INTERNAL_AUTHORITY = "ROLE_INTERNAL";

    private static final Logger log = LoggerFactory.getLogger(InternalApiKeyFilter.class);

    /** Decodes and cleans the request path the same way Spring MVC does before matching handlers. */
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final byte[] expectedKey;

    public InternalApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey == null || expectedKey.isBlank()
                ? null
                : expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decides on the decoded path within the application, lower-cased, not on the raw request URI.
     * Spring MVC routes {@code /%69nternal/...} to the internal handlers after decoding it, so a
     * raw-URI prefix check would let such a request skip the key check entirely. The security
     * chain's {@code hasAuthority(ROLE_INTERNAL)} rule backs this up.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request).toLowerCase(Locale.ROOT);
        return !path.startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (expectedKey == null) {
            log.error("SECURITY rejected {} {}: jihajoor.payment.internal-api-key is not configured",
                    request.getMethod(), request.getRequestURI());
            reject(response, "Internal API is not available");
            return;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || !constantTimeEquals(presented)) {
            // Never log the presented value; it is a credential.
            log.warn("SECURITY rejected {} {}: missing or invalid internal API key",
                    request.getMethod(), request.getRequestURI());
            reject(response, "Invalid internal credentials");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "internal-service", null,
                        List.of(new SimpleGrantedAuthority(INTERNAL_AUTHORITY))));
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean constantTimeEquals(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"errorCode\":\"INTERNAL_AUTH_FAILED\",\"message\":\"" + message + "\"}");
    }
}
