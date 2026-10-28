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

    private final JdbcTemplate jdbcTemplate;

    public JdbcAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
