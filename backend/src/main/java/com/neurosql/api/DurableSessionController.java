package com.neurosql.api;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.neurosql.security.DurableLoginService;
import com.neurosql.security.DurableSessionAuthenticationFilter;
import com.neurosql.security.SessionLifecycleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

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
    private final DurableLoginService loginService;

    public DurableSessionController(
            SessionLifecycleService sessions,
            DurableLoginService loginService) {
        this.sessions = sessions;
        this.loginService = loginService;
    }

    /**
     * Authenticates a pre-provisioned, active, email-verified account and issues
     * an opaque bearer session. Registration and email verification are not
     * implemented by this endpoint.
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        DurableLoginService.LoginAttempt attempt = loginService.login(request.email(), request.password());

        if (attempt.status() == DurableLoginService.Status.RATE_LIMITED) {
            long retryAfter = Math.max(1, attempt.retryAfterSeconds());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(new LoginError("Too many login attempts. Try again later."));
        }

        if (attempt.status() != DurableLoginService.Status.AUTHENTICATED || attempt.session() == null) {
            return loginUnauthorized();
        }

        SessionLifecycleService.IssuedSession issued = attempt.session();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new LoginResponse(issued.token(), issued.expiresAt()));
    }

    /**
     * Returns only the identity and expiry associated with the current durable
     * bearer session. The raw token and database details are never returned.
     */
    @GetMapping("/me")
    public ResponseEntity<SessionIdentity> me(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof DurableSessionAuthenticationFilter.SessionPrincipal principal)
                || principal.userId() == null
                || principal.sessionId() == null
                || principal.expiresAt() == null
                || !principal.expiresAt().isAfter(Instant.now())) {
            return unauthorized();
        }

        SessionIdentity identity = new SessionIdentity(
                principal.userId(), principal.sessionId(), principal.expiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(identity);
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

    private static ResponseEntity<LoginError> loginUnauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new LoginError("Invalid email or password."));
    }

    private static <T> ResponseEntity<T> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"NeuroSQL\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 256) String password) {
    }

    public record LoginResponse(String token, Instant expiresAt) {
    }

    public record LoginError(String message) {
    }

    public record SessionIdentity(UUID userId, UUID sessionId, Instant expiresAt) {
    }
}
