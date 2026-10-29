package com.neurosql.security;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC persistence adapter for the proposed durable-session schema.
 *
 * <p>This bean is deliberately disabled unless
 * {@code NEUROSQL_AUTH_DURABLE_SESSIONS_ENABLED=true}. The schema is still
 * awaiting approval, so do not enable this flag or wire it to authentication
 * until the migration and least-privileged database grants are approved.</p>
 *
 * <p>Methods accept only a SHA-256 token digest; raw bearer tokens must never
 * be passed to or stored by this repository.</p>
 */
@Repository
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class JdbcSessionRepository {
    static final String INSERT_SESSION_SQL = """
            INSERT INTO neurosql_app.user_sessions
                (user_id, token_hash, created_at, expires_at)
            VALUES (?, ?, ?, ?)
            """;

    static final String FIND_ACTIVE_SESSION_SQL = """
            SELECT s.session_id, s.user_id, s.created_at, s.expires_at
            FROM neurosql_app.user_sessions s
            JOIN neurosql_app.app_users u ON u.user_id = s.user_id
            WHERE s.token_hash = ?
              AND s.revoked_at IS NULL
              AND s.created_at <= ?
              AND s.expires_at > ?
              AND s.expires_at <= s.created_at + INTERVAL '12 hours'
              AND u.status = 'ACTIVE'
            """;

    static final String REVOKE_SESSION_SQL = """
            UPDATE neurosql_app.user_sessions
            SET revoked_at = GREATEST(?, created_at)
            WHERE token_hash = ?
              AND revoked_at IS NULL
            """;

    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbcTemplate;

    public JdbcSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Persists a session digest with a fixed expiry no later than twelve hours
     * after issuance. The caller is responsible for issuing and returning the
     * raw token to the client.
     */
    public void createSession(UUID userId, String tokenHash, Instant issuedAt, Instant expiresAt) {
        if (userId == null || issuedAt == null || expiresAt == null || !isValidHash(tokenHash)) {
            throw new IllegalArgumentException("A user, canonical token hash, issue time, and expiry are required.");
        }

        final Instant maximumExpiry;
        try {
            maximumExpiry = issuedAt.plus(SessionLifetimePolicy.MAX_SESSION_LIFETIME);
        } catch (ArithmeticException | DateTimeException invalidIssueTime) {
            throw new IllegalArgumentException("Session issue time is outside the supported range.", invalidIssueTime);
        }
        if (!expiresAt.isAfter(issuedAt) || expiresAt.isAfter(maximumExpiry)) {
            throw new IllegalArgumentException("Session expiry must be after issuance and no later than twelve hours later.");
        }

        jdbcTemplate.update(
                INSERT_SESSION_SQL,
                userId,
                tokenHash,
                Timestamp.from(issuedAt),
                Timestamp.from(expiresAt));
    }

    /**
     * Finds a usable session only if its user is active, it has not been
     * revoked, and its stored lifetime satisfies the fixed twelve-hour policy.
     */
    public Optional<StoredSession> findActiveByTokenHash(String tokenHash, Instant now) {
        if (!isValidHash(tokenHash) || now == null) {
            return Optional.empty();
        }

        List<StoredSession> matches = jdbcTemplate.query(
                FIND_ACTIVE_SESSION_SQL,
                (RowMapper<StoredSession>) (resultSet, rowNumber) -> new StoredSession(
                        resultSet.getObject("session_id", UUID.class),
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("expires_at").toInstant()),
                tokenHash,
                Timestamp.from(now),
                Timestamp.from(now));
        return matches.stream().findFirst();
    }

    /**
     * Revokes a session once. Repeated calls are harmless; a timestamp earlier
     * than session creation is raised to the creation time to satisfy the schema
     * constraint even if the caller's clock is skewed.
     */
    public boolean revokeByTokenHash(String tokenHash, Instant revokedAt) {
        if (!isValidHash(tokenHash) || revokedAt == null) {
            return false;
        }
        return jdbcTemplate.update(REVOKE_SESSION_SQL, Timestamp.from(revokedAt), tokenHash) > 0;
    }

    private boolean isValidHash(String tokenHash) {
        return tokenHash != null && TOKEN_HASH_PATTERN.matcher(tokenHash).matches();
    }

    public record StoredSession(UUID sessionId, UUID userId, Instant issuedAt, Instant expiresAt) {
    }
}
