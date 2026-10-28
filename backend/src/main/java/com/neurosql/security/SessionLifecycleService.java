package com.neurosql.security;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Orchestrates opaque session issuance, validation, and revocation.
 *
 * <p>This service is disabled unless
 * {@code NEUROSQL_AUTH_DURABLE_SESSIONS_ENABLED=true}. It is intentionally not
 * exposed through HTTP authentication or a controller while the durable-auth
 * schema and runtime database grants await approval.</p>
 *
 * <p>The raw token is returned once to the caller. Only its SHA-256 digest is
 * passed to the persistence adapter. Callers must not log the raw token.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class SessionLifecycleService {
    private final SessionTokenCodec tokenCodec;
    private final SessionLifetimePolicy lifetimePolicy;
    private final JdbcSessionRepository sessions;

    public SessionLifecycleService(JdbcSessionRepository sessions) {
        this(new SessionTokenCodec(), new SessionLifetimePolicy(), sessions);
    }

    SessionLifecycleService(
            SessionTokenCodec tokenCodec,
            SessionLifetimePolicy lifetimePolicy,
            JdbcSessionRepository sessions) {
        this.tokenCodec = Objects.requireNonNull(tokenCodec, "tokenCodec");
        this.lifetimePolicy = Objects.requireNonNull(lifetimePolicy, "lifetimePolicy");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /** Creates a fixed-lifetime session and returns the raw token only once. */
    public IssuedSession issueSession(UUID userId, Instant issuedAt) {
        if (userId == null || issuedAt == null) {
            throw new IllegalArgumentException("A user and session issue time are required.");
        }

        final Instant expiresAt;
        try {
            expiresAt = lifetimePolicy.expiresAt(issuedAt);
        } catch (ArithmeticException | DateTimeException invalidIssueTime) {
            throw new IllegalArgumentException("Session issue time is outside the supported range.", invalidIssueTime);
        }

        String rawToken = tokenCodec.issueToken();
        String tokenHash = tokenCodec.hashToken(rawToken);
        sessions.createSession(userId, tokenHash, issuedAt, expiresAt);
        return new IssuedSession(rawToken, issuedAt, expiresAt);
    }

    /**
     * Resolves a token only through its digest. Invalid tokens and missing
     * timestamps fail closed without querying persistence.
     */
    public Optional<JdbcSessionRepository.StoredSession> validateSession(String rawToken, Instant now) {
        if (now == null) {
            return Optional.empty();
        }

        final String tokenHash;
        try {
            tokenHash = tokenCodec.hashToken(rawToken);
        } catch (IllegalArgumentException invalidToken) {
            return Optional.empty();
        }
        return sessions.findActiveByTokenHash(tokenHash, now);
    }

    /**
     * Revokes a token by digest. Expired sessions can still be revoked during
     * logout; repeated revocation is handled idempotently by the repository.
     */
    public boolean revokeSession(String rawToken, Instant revokedAt) {
        if (revokedAt == null) {
            return false;
        }

        final String tokenHash;
        try {
            tokenHash = tokenCodec.hashToken(rawToken);
        } catch (IllegalArgumentException invalidToken) {
            return false;
        }
        return sessions.revokeByTokenHash(tokenHash, revokedAt);
    }

    /** The token's string representation intentionally redacts the bearer secret. */
    public record IssuedSession(String token, Instant issuedAt, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedSession[ token=[REDACTED], issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + " ]";
        }
    }
}
