package com.jihajoor.auth.api;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.jihajoor.auth.config.AuthTokenProperties;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Keeps the web apps' refresh token out of JavaScript's reach.
 *
 * <p>A browser app sends {@code X-Session-Transport: cookie} on its auth calls. For those, every
 * response carrying a {@link TokenResponse} puts the refresh token in an {@code HttpOnly; Secure;
 * SameSite=Strict} cookie scoped to the auth path and leaves it out of the JSON, so script
 * injected into the page can never read it; refresh and logout read it back from the cookie.
 * The native (Capacitor) apps, which cannot rely on cookies, do not send the header and keep
 * receiving the token in the body.
 */
@ControllerAdvice
public class RefreshCookie implements ResponseBodyAdvice<Object> {

    public static final String TRANSPORT_HEADER = "X-Session-Transport";
    public static final String COOKIE_TRANSPORT = "cookie";
    public static final String COOKIE_NAME = "jh_refresh";

    private final String path;
    private final boolean secure;
    private final Duration ttl;

    public RefreshCookie(AuthTokenProperties tokens,
                         @Value("${jihajoor.auth.refresh-cookie.path:/api/auth}") String path,
                         @Value("${jihajoor.auth.refresh-cookie.secure:true}") boolean secure) {
        this.path = path;
        this.secure = secure;
        this.ttl = tokens.getRefreshTtl();
    }

    /** True when the caller asked for the refresh token in a cookie. */
    public static boolean wantsCookie(HttpServletRequest request) {
        return COOKIE_TRANSPORT.equalsIgnoreCase(request.getHeader(TRANSPORT_HEADER));
    }

    /** The refresh token from the body when given, else from the cookie; null when neither. */
    public static String resolve(String fromBody, HttpServletRequest request) {
        if (fromBody != null && !fromBody.isBlank()) {
            return fromBody;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** The Set-Cookie value that stores {@code token}, or that clears the cookie when null. */
    public String header(String token) {
        return ResponseCookie.from(COOKIE_NAME, token == null ? "" : token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(path)
                .maxAge(token == null ? Duration.ZERO : ttl)
                .build()
                .toString();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object payload, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(payload instanceof TokenResponse body) || body.refreshToken() == null
                || !COOKIE_TRANSPORT.equalsIgnoreCase(request.getHeaders().getFirst(TRANSPORT_HEADER))) {
            return payload;
        }
        response.getHeaders().add(HttpHeaders.SET_COOKIE, header(body.refreshToken()));
        return new TokenResponse(body.userId(), body.roles(), body.accessToken(), null, body.tokenType(),
                body.expiresInSeconds());
    }
}
