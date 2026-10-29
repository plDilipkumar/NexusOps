package com.neurosql.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter for proposed single-use email-verification and password-reset tokens.
 *
 * <p>This bean remains disabled until the durable-auth schema and shared limiter
 * are approved and provisioned. It persists only token digests, never raw tokens.</p>
 */
@Repository
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = {"durable-sessions-enabled", "shared-rate-limit-enabled"},
        havingValue = "true")
public class JdbcAccountActionTokenRepository {
    static final String INSERT_TOKEN_SQL = """
            INSERT INTO neurosql_app.account_action_tokens
                (user_id, purpose, token_hash, expires_at)
            VALUES (?, ?, ?, now() + make_interval(secs => ?::double precision))
            RETURNING expires_at
            """;

    static final String CONSUME_TOKEN_SQL = """
            UPDATE neurosql_app.account_action_tokens
            SET consumed_at = now()
            WHERE token_hash = ?
              AND purpose = ?
              AND consumed_at IS NULL
              AND expires_at > now()
            RETURNING user_id
            """;

    static final String VERIFY_EMAIL_SQL = """
            WITH consumed AS (
                UPDATE neurosql_app.account_action_tokens
                SET consumed_at = now()
                WHERE token_hash = ?
                  AND purpose = 'EMAIL_VERIFICATION'
                  AND consumed_at IS NULL
                  AND expires_at > now()
                RETURNING user_id
            ),
            verified AS (
                UPDATE neurosql_app.app_users AS users
                SET email_verified_at = now(),
                    updated_at = now()
                FROM consumed
                WHERE users.user_id = consumed.user_id
                  AND users.status = 'ACTIVE'
                  AND users.email_verified_at IS NULL
                RETURNING users.user_id
            )
            SELECT user_id FROM verified
            """;

    static final String CONSUME_OUTSTANDING_TOKENS_SQL = """
            UPDATE neurosql_app.account_action_tokens
               SET consumed_at = now()
             WHERE user_id = ?
               AND purpose = ?
               AND consumed_at IS NULL
            """;

    private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbcTemplate;

    public JdbcAccountActionTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Stores a digest and asks PostgreSQL to calculate expiry using its own clock,
     * avoiding app/database clock skew around the created_at constraint.
     *
     * @return the database-calculated expiry timestamp
     */
    public Instant createToken(
            UUID userId,
            AccountActionTokenService.Purpose purpose,
            String tokenHash,
            long lifetimeSeconds) {
        if (userId == null || purpose == null || !isValidHash(tokenHash)
                || lifetimeSeconds < 1 || lifetimeSeconds > purpose.lifetime().getSeconds()) {
            throw new IllegalArgumentException("A user, valid purpose, token digest, and supported lifetime are required.");
        }

        Timestamp expiresAt = jdbcTemplate.queryForObject(
                INSERT_TOKEN_SQL,
                (resultSet, rowNumber) -> resultSet.getTimestamp("expires_at"),
                userId,
                purpose.name(),
                tokenHash,
                lifetimeSeconds);
        if (expiresAt == null) {
            throw new IllegalStateException("The database did not return an account-action token expiry.");
        }
        return expiresAt.toInstant();
    }

    /**
     * Atomically consumes one unexpired token for the expected purpose.
     * Concurrent replays can update at most one row because the predicate
     * requires consumed_at IS NULL in the same UPDATE statement.
     */
    public Optional<UUID> consumeToken(String tokenHash, AccountActionTokenService.Purpose purpose) {
        if (!isValidHash(tokenHash) || purpose == null) {
            return Optional.empty();
        }

        List<UUID> users = jdbcTemplate.query(
                CONSUME_TOKEN_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class),
                tokenHash,
                purpose.name());
        return users.stream().findFirst();
    }

    /**
     * Invalidates every outstanding token for one user and purpose before a replacement
     * is issued. The database trigger makes this transition irreversible.
     *
     * @return the number of tokens invalidated
     */
    public int consumeOutstandingTokens(UUID userId, AccountActionTokenService.Purpose purpose) {
        if (userId == null || purpose == null) {
            throw new IllegalArgumentException("A user and account-action purpose are required.");
        }
        return jdbcTemplate.update(CONSUME_OUTSTANDING_TOKENS_SQL, userId, purpose.name());
    }

    /**
     * Consumes a valid email-verification token and marks the active account verified
     * in the same PostgreSQL statement. Invalid, expired, replayed, wrong-purpose,
     * already-verified, or inactive-account cases return false without exposing identity.
     */
    public boolean consumeEmailVerificationToken(String tokenHash) {
        if (!isValidHash(tokenHash)) {
            return false;
        }

        List<UUID> verifiedUsers = jdbcTemplate.query(
                VERIFY_EMAIL_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class),
                tokenHash);
        return !verifiedUsers.isEmpty();
    }

    private static boolean isValidHash(String tokenHash) {
        return tokenHash != null && TOKEN_HASH_PATTERN.matcher(tokenHash).matches();
    }
}
