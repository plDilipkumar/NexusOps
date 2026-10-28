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
 * Exercises the real JDBC adapter against an isolated PostgreSQL instance.
 *
 * <p>Only runs when the dedicated CI job provides NEUROSQL_TEST_JDBC_URL. The
 * job uses a disposable PostgreSQL container; these fixture tables are not a
 * production migration and must never be pointed at a shared or production DB.</p>
 */
@EnabledIfEnvironmentVariable(named = "NEUROSQL_TEST_JDBC_URL", matches = "^jdbc:postgresql:.*$")
class JdbcSessionRepositoryPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-28T08:30:00Z");

    private JdbcTemplate jdbc;
    private JdbcSessionRepository repository;

    @BeforeEach
    void setUpIsolatedPostgresFixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv("NEUROSQL_TEST_JDBC_URL"));
        dataSource.setUsername(System.getenv("NEUROSQL_TEST_DB_USERNAME"));
        dataSource.setPassword(System.getenv("NEUROSQL_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);

        // The CI job provisions a fresh disposable database for this class.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS neurosql_app");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.app_users (
                    user_id UUID PRIMARY KEY,
                    status VARCHAR(24) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.user_sessions (
                    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id UUID NOT NULL REFERENCES neurosql_app.app_users(user_id) ON DELETE CASCADE,
                    token_hash VARCHAR(64) NOT NULL UNIQUE
                        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
                    created_at TIMESTAMPTZ NOT NULL,
                    expires_at TIMESTAMPTZ NOT NULL,
                    revoked_at TIMESTAMPTZ,
                    CHECK (expires_at > created_at),
                    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
                )
                """);
        repository = new JdbcSessionRepository(jdbc);
    }

    @Test
    void persistsDigestReadsActiveSessionAndRevokesIdempotently() {
        UUID userId = insertUser("ACTIVE");
        String digest = newDigest();
        Instant issuedAt = NOW.minusSeconds(60);
        Instant expiresAt = NOW.plusSeconds(60 * 60);

        repository.createSession(userId, digest, issuedAt, expiresAt);

        JdbcSessionRepository.StoredSession stored =
                repository.findActiveByTokenHash(digest, NOW).orElseThrow();
        assertThat(stored.userId()).isEqualTo(userId);
        assertThat(stored.issuedAt()).isEqualTo(issuedAt);
        assertThat(stored.expiresAt()).isEqualTo(expiresAt);
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM neurosql_app.user_sessions WHERE user_id = ?",
                String.class,
                userId)).isEqualTo(digest);

        // Simulate a clock behind session creation: the database must preserve its CHECK constraint.
        assertThat(repository.revokeByTokenHash(digest, issuedAt.minusSeconds(30))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at FROM neurosql_app.user_sessions WHERE token_hash = ?",
                Timestamp.class,
                digest).toInstant()).isEqualTo(issuedAt);
        assertThat(repository.findActiveByTokenHash(digest, NOW)).isEmpty();
        assertThat(repository.revokeByTokenHash(digest, NOW)).isFalse();
    }

    @Test
    void lookupRejectsExpiredFutureIssuedInactiveAndOverlongSessions() {
        UUID activeUser = insertUser("ACTIVE");
        UUID inactiveUser = insertUser("DISABLED");

        Instant expiredIssue = NOW.minusSeconds(3600);
        String expiredDigest = newDigest();
        repository.createSession(
                activeUser, expiredDigest, expiredIssue, NOW.minusSeconds(1));

        Instant futureIssue = NOW.plusSeconds(60);
        String futureDigest = newDigest();
        repository.createSession(
                activeUser, futureDigest, futureIssue, futureIssue.plusSeconds(3600));

        Instant inactiveIssue = NOW.minusSeconds(60);
        String inactiveDigest = newDigest();
        repository.createSession(
                inactiveUser, inactiveDigest, inactiveIssue, NOW.plusSeconds(3600));

        Instant overlongIssue = NOW.minusSeconds(60);
        String overlongDigest = newDigest();
        // Insert directly to simulate corrupted/legacy data bypassing Java-side validation.
        jdbc.update("""
                INSERT INTO neurosql_app.user_sessions
                    (user_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?)
                """,
                activeUser,
                overlongDigest,
                Timestamp.from(overlongIssue),
                Timestamp.from(overlongIssue.plusSeconds(13 * 60 * 60)));

        assertThat(repository.findActiveByTokenHash(newDigest(), NOW)).isEmpty();
        assertThat(repository.findActiveByTokenHash(expiredDigest, NOW)).isEmpty();
        assertThat(repository.findActiveByTokenHash(futureDigest, NOW)).isEmpty();
        assertThat(repository.findActiveByTokenHash(inactiveDigest, NOW)).isEmpty();
        assertThat(repository.findActiveByTokenHash(overlongDigest, NOW)).isEmpty();
    }

    private UUID insertUser(String status) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO neurosql_app.app_users (user_id, status) VALUES (?, ?)",
                userId,
                status);
        return userId;
    }

    private static String newDigest() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }
}
