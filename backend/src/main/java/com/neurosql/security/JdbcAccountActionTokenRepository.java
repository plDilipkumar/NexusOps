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

    private static boolean isValidHash(String tokenHash) {
        return tokenHash != null && TOKEN_HASH_PATTERN.matcher(tokenHash).matches();
    }
}
