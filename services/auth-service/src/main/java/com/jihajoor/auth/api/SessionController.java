package com.jihajoor.auth.api;

import org.springframework.http.HttpHeaders;
import com.jihajoor.auth.token.TokenException;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jihajoor.auth.social.SocialLoginService;
import com.jihajoor.auth.social.SocialLoginService.SocialLoginResult;
import com.jihajoor.auth.token.RefreshService;
import com.jihajoor.auth.token.RefreshService.RefreshResult;

import jakarta.validation.Valid;

/**
 * Session lifecycle endpoints: refresh-token rotation, social login, and logout
 * (Requirement 1.5, 1.9, 1.10, 1.12).
 */
@RestController
@RequestMapping("/auth")
public class SessionController {

    private final RefreshService refreshService;
    private final SocialLoginService socialLoginService;
    private final RefreshCookie refreshCookie;

    public SessionController(RefreshService refreshService, SocialLoginService socialLoginService,
                             RefreshCookie refreshCookie) {
        this.refreshService = refreshService;
        this.socialLoginService = socialLoginService;
        this.refreshCookie = refreshCookie;
    }

    /**
     * {@code POST /auth/token/refresh} — issue a new access token and rotate the refresh token
     * (Requirement 1.9). Replay of a used token invalidates the whole family and returns 401
     * (Requirement 1.10, Property 26).
     */
    @PostMapping("/token/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody(required = false) RefreshRequest request,
                                                 HttpServletRequest http) {
        // A web app sends no token: it lives in the HttpOnly cookie (see RefreshCookie).
        String token = RefreshCookie.resolve(request == null ? null : request.refreshToken(), http);
        if (token == null) {
            throw TokenException.invalidRefreshToken();
        }
        RefreshResult result = refreshService.refresh(token);
        return ResponseEntity.ok(TokenResponse.from(result.userId(), result.roles(), result.tokens()));
    }

    /**
     * {@code POST /auth/login/social} — validate a Google/Apple identity token, create or
     * retrieve the account, and return tokens (Requirement 1.5). Invalid/expired identity
     * tokens return 401 with an error code (handled by the exception advice).
     */
    @PostMapping("/login/social")
    public ResponseEntity<TokenResponse> socialLogin(@Valid @RequestBody SocialLoginRequest request) {
        SocialLoginResult result = socialLoginService.login(request.provider(), request.identityToken());
        return ResponseEntity.ok(TokenResponse.from(result.userId(), result.roles(), result.tokens()));
    }

    /**
     * {@code POST /auth/logout} — revoke the refresh token within the request cycle
     * (Requirement 1.12). Idempotent: returns 204 even if the token was already revoked.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request,
                                       HttpServletRequest http) {
        String token = RefreshCookie.resolve(request == null ? null : request.refreshToken(), http);
        if (token != null) {
            refreshService.logout(token);
        }
        // Always clear the cookie, so a signed-out browser holds nothing.
        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, refreshCookie.header(null))
                .build();
    }
}
