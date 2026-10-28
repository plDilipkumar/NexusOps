package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
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
@EnabledIfEnvironmentVariable(named = "NEUROSQL_TEST_JDBC_URL", matches = "^jdbc:postgresql:.*$")
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
