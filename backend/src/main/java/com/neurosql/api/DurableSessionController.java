package com.neurosql.api;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.SessionLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Durable-session endpoints. This controller is absent unless durable sessions
 * are explicitly enabled after the schema and database grants have been approved.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class DurableSessionController {
    private static final Pattern BEARER_TOKEN =
            Pattern.compile("(?i)^Bearer ([A-Za-z0-9_-]{43})$");

    private final SessionLifecycleService sessions;

    public DurableSessionController(SessionLifecycleService sessions) {
        this.sessions = sessions;
    }

    /**
     * Revokes the current bearer session. Basic-auth owners and unauthenticated
     * requests cannot use this endpoint to revoke durable sessions.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof DurableSessionAuthenticationFilter.SessionPrincipal)) {
            return unauthorized();
        }

        Matcher matcher = authorization == null ? null : BEARER_TOKEN.matcher(authorization);
        if (matcher == null || !matcher.matches()) {
            return unauthorized();
        }

        // The filter has already validated this token. Do not log or return it.
        // A concurrent logout may have revoked it first; the response stays idempotent.
        sessions.revokeSession(matcher.group(1), Instant.now());
        return ResponseEntity.noContent()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private static ResponseEntity<Void> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
