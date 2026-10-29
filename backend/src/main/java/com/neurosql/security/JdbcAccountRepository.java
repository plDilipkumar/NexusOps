package com.neurosql.security;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads credentials only for active accounts with verified email addresses.
 * This adapter targets the proposed durable-auth schema and is disabled until
 * that schema and the runtime database grants are approved.
 */
@Repository
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class JdbcAccountRepository {
    static final String FIND_ACTIVE_VERIFIED_ACCOUNT_SQL = """
            SELECT user_id, password_hash
            FROM neurosql_app.app_users
            WHERE lower(email) = ?
              AND status = 'ACTIVE'
              AND email_verified_at IS NOT NULL
            LIMIT 1
            """;

    static final String CREATE_UNVERIFIED_ACCOUNT_SQL = """
            INSERT INTO neurosql_app.app_users (email, password_hash, display_name)
            VALUES (?, ?, ?)
            ON CONFLICT (lower(email)) DO NOTHING
            RETURNING user_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates an active-but-unverified account using only the columns granted to
     * the proposed runtime role. A case-insensitive duplicate email is a no-op;
     * callers must return the same public response for both outcomes.
     *
     * @return the new user id, or empty when the email already exists
     */
    public Optional<UUID> createUnverifiedAccount(String email, String passwordHash, String displayName) {
        String normalizedEmail = normalizeRegistrationEmail(email);
        String normalizedDisplayName = normalizeDisplayName(displayName);
        if (passwordHash == null || passwordHash.length() < 50 || passwordHash.length() > 255) {
            throw new IllegalArgumentException("A supported encoded password hash is required.");
        }

        List<UUID> createdUsers = jdbcTemplate.query(
                CREATE_UNVERIFIED_ACCOUNT_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class),
                normalizedEmail,
                passwordHash,
                normalizedDisplayName);
        return createdUsers.stream().findFirst();
    }

    private static String normalizeRegistrationEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("A valid email address is required.");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (normalized.length() > 254
                || at <= 0
                || at > 64
                || at != normalized.lastIndexOf('@')
                || at == normalized.length() - 1
                || !normalized.substring(at + 1).contains(".")
                || normalized.startsWith(".")
                || normalized.endsWith(".")
                || normalized.contains("..")
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("A valid email address is required.");
        }
        return normalized;
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw new IllegalArgumentException("A display name is required.");
        }
        String normalized = displayName.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 120 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A display name of 1 to 120 printable characters is required.");
        }
        return normalized;
    }

    public Optional<LoginAccount> findActiveVerifiedByEmail(String email) {
        if (email == null || email.isBlank() || email.length() > 254) {
            return Optional.empty();
        }
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty() || normalizedEmail.length() > 254) {
            return Optional.empty();
        }

        List<LoginAccount> matches = jdbcTemplate.query(
                FIND_ACTIVE_VERIFIED_ACCOUNT_SQL,
                (resultSet, rowNumber) -> new LoginAccount(
                        resultSet.getObject("user_id", UUID.class),
                        resultSet.getString("password_hash")),
                normalizedEmail);
        return matches.stream().findFirst();
    }

    public record LoginAccount(UUID userId, String passwordHash) {
    }
}
