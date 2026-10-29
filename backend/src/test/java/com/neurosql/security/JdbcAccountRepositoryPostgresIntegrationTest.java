package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises account lookup SQL against the disposable PostgreSQL 17 CI database.
 * This creates only nullable test-fixture columns, not a production migration.
 */
@EnabledIfEnvironmentVariable(named = "NEUROSQL_TEST_JDBC_URL", matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
class JdbcAccountRepositoryPostgresIntegrationTest {
    private JdbcTemplate jdbc;
    private JdbcAccountRepository repository;

    @BeforeEach
    void setUpDisposablePostgresFixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv("NEUROSQL_TEST_JDBC_URL"));
        dataSource.setUsername(System.getenv("NEUROSQL_TEST_DB_USERNAME"));
        dataSource.setPassword(System.getenv("NEUROSQL_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS neurosql_app");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.app_users (
                    user_id UUID PRIMARY KEY,
                    status VARCHAR(24) NOT NULL
                )
                """);
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS email VARCHAR(320)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS display_name VARCHAR(120)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ALTER COLUMN user_id SET DEFAULT gen_random_uuid()");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ALTER COLUMN status SET DEFAULT 'ACTIVE'");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_app_users_email_lower ON neurosql_app.app_users (lower(email))");
        repository = new JdbcAccountRepository(jdbc);
    }

    @Test
    void findsOnlyActiveVerifiedAccountsAndNormalizesEmail() {
        String suffix = UUID.randomUUID().toString();
        UUID activeUser = insertAccount("alice-" + suffix + "@example.test", "ACTIVE", Instant.parse("2026-10-28T10:00:00Z"));
        insertAccount("unverified-" + suffix + "@example.test", "ACTIVE", null);
        insertAccount("disabled-" + suffix + "@example.test", "DISABLED", Instant.parse("2026-10-28T10:00:00Z"));

        JdbcAccountRepository.LoginAccount found =
                repository.findActiveVerifiedByEmail("  ALICE-" + suffix + "@EXAMPLE.TEST ").orElseThrow();

        assertThat(found.userId()).isEqualTo(activeUser);
        assertThat(found.passwordHash()).isEqualTo("stored-hash-fixture");
        assertThat(repository.findActiveVerifiedByEmail("unverified-" + suffix + "@example.test")).isEmpty();
        assertThat(repository.findActiveVerifiedByEmail("disabled-" + suffix + "@example.test")).isEmpty();
    }

    @Test
    void treatsEmailAsAParameterAndRejectsInvalidLengths() {
        assertThat(repository.findActiveVerifiedByEmail("' OR 1=1 --")).isEmpty();
        assertThat(repository.findActiveVerifiedByEmail("x".repeat(255) + "@example.test")).isEmpty();
        assertThat(repository.findActiveVerifiedByEmail(null)).isEmpty();
    }

    @Test
    void createsUnverifiedAccountAndTreatsCaseInsensitiveDuplicatesAsNoOp() {
        String suffix = UUID.randomUUID().toString();
        String email = "registration-" + suffix + "@example.test";
        String passwordHash = "$2a$10$" + "x".repeat(53);

        UUID userId = repository.createUnverifiedAccount(
                "  " + email.toUpperCase(Locale.ROOT) + "  ",
                passwordHash,
                " Registration Test ")
                .orElseThrow();

        assertThat(jdbc.queryForObject(
                "SELECT email FROM neurosql_app.app_users WHERE user_id = ?",
                String.class,
                userId)).isEqualTo(email);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM neurosql_app.app_users WHERE user_id = ?",
                String.class,
                userId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT email_verified_at IS NULL FROM neurosql_app.app_users WHERE user_id = ?",
                Boolean.class,
                userId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM neurosql_app.app_users WHERE user_id = ?",
                String.class,
                userId)).isEqualTo("Registration Test");

        assertThat(repository.createUnverifiedAccount(email, passwordHash, "Duplicate User")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.app_users WHERE lower(email) = ?",
                Integer.class,
                email)).isEqualTo(1);
    }

    @Test
    void findsOnlyActiveUnverifiedAccountsAndNormalizesEmailForResend() {
        String suffix = UUID.randomUUID().toString();
        String pendingEmail = "pending-" + suffix + "@example.test";
        UUID pendingUser = insertAccount(pendingEmail, "ACTIVE", null);
        insertAccount("verified-" + suffix + "@example.test", "ACTIVE",
                Instant.parse("2026-10-28T10:00:00Z"));
        insertAccount("disabled-" + suffix + "@example.test", "DISABLED", null);

        assertThat(repository.findActiveUnverifiedByEmailForUpdate(
                "  " + pendingEmail.toUpperCase(Locale.ROOT) + "  "))
                .contains(new JdbcAccountRepository.UnverifiedAccount(pendingUser, pendingEmail));
        assertThat(repository.findActiveUnverifiedByEmailForUpdate(
                "verified-" + suffix + "@example.test")).isEmpty();
        assertThat(repository.findActiveUnverifiedByEmailForUpdate(
                "disabled-" + suffix + "@example.test")).isEmpty();
        assertThat(repository.findActiveUnverifiedByEmailForUpdate("not-an-email")).isEmpty();
    }

    @Test
    void rejectsInvalidAccountFieldsBeforeIssuingInsert() {
        String passwordHash = "$2a$10$" + "x".repeat(53);

        assertThatThrownBy(() -> repository.createUnverifiedAccount("not-an-email", passwordHash, "Test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.createUnverifiedAccount("valid@example.test", "short", "Test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.createUnverifiedAccount(
                "valid@example.test", passwordHash, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.app_users WHERE email = 'valid@example.test'",
                Integer.class)).isZero();
    }

    private UUID insertAccount(String email, String status, Instant verifiedAt) {
        UUID userId = UUID.randomUUID();
        if (verifiedAt == null) {
            jdbc.update("""
                    INSERT INTO neurosql_app.app_users
                        (user_id, status, email, password_hash, display_name, email_verified_at)
                    VALUES (?, ?, ?, ?, ?, NULL)
                    """,
                    userId, status, email, "stored-hash-fixture", "Test User");
        } else {
            jdbc.update("""
                    INSERT INTO neurosql_app.app_users
                        (user_id, status, email, password_hash, display_name, email_verified_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    userId, status, email, "stored-hash-fixture", "Test User", Timestamp.from(verifiedAt));
        }
        return userId;
    }
}
