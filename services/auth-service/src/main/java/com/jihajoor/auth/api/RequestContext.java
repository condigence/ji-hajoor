package com.jihajoor.auth.api;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jihajoor.auth.admin.AdminUserException;
import com.jihajoor.auth.admin.StaffActor;

/** Who is calling, and from where, for the controllers that need it. */
final class RequestContext {

    private static final String ROLE_PREFIX = "ROLE_";

    private RequestContext() {
    }

    /**
     * The caller as the JWT validation filter left them in the security context: the subject as the
     * account id, the {@code ROLE_*} authorities as role names.
     *
     * @throws AdminUserException 401 when there is no usable principal
     */
    static StaffActor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw AdminUserException.invalidPrincipal();
        }
        UUID actorId;
        try {
            actorId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw AdminUserException.invalidPrincipal();
        }
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toSet());
        return new StaffActor(actorId, roles);
    }

    /**
     * The client address for rate limits (email-auth Requirement 8.1). Railway's edge sets
     * {@code X-Real-IP} to the visitor's address and the web apps' nginx forwards it, so it wins.
     * Otherwise the last {@code X-Forwarded-For} entry (the address our own proxy saw; earlier
     * entries come from the client and are not trusted), and failing that the socket peer.
     */
    static String clientIp(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && real.length() <= 64) {
            return real.strip();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String last = hops[hops.length - 1].strip();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
