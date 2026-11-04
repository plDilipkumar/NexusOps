package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Exercises the real account lookup, login service, session lifecycle, and JDBC
 * session persistence against the disposable PostgreSQL 17 CI database.
 *
 * <p>This test creates only an isolated test fixture. Never point
 * NEUROSQL_TEST_JDBC_URL at a shared or production database.</p>
 */
@EnabledIfEnvironmentVariable(named = "NEUROSQL_TEST_JDBC_URL", matches = "^jdbc:postgresql:.*$")
class DurableLoginPostgresIntegrationTest {
    private static final String PASSWORD = "Correct-Horse-Battery-2026";

    private JdbcTemplate jdbc;
    private Clock clock;
    private PasswordEncoder passwordEncoder;
    private SessionTokenCodec tokenCodec;
    private SessionLifecycleService sessions;
    private DurableLoginService loginService;

    @BeforeEach
    void setUpDisposablePostgresFixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv("NEUROSQL_TEST_JDBC_URL"));
        dataSource.setUsername(System.getenv("NEUROSQL_TEST_DB_USERNAME"));
        dataSource.setPassword(System.getenv("NEUROSQL_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);

        // Reuse the disposable CI database without dropping objects created by other integration tests.
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

        clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        passwordEncoder = new BCryptPasswordEncoder();
        tokenCodec = new SessionTokenCodec();
        JdbcAccountRepository accounts = new JdbcAccountRepository(jdbc);
        JdbcSessionRepository sessionRepository = new JdbcSessionRepository(jdbc);
        sessions = new SessionLifecycleService(tokenCodec, new SessionLifetimePolicy(), sessionRepository);
        BasicAuthAttemptRateLimiter limiter = new BasicAuthAttemptRateLimiter(clock);
        loginService = new DurableLoginService(
                accounts,
                passwordEncoder,
                sessions,
                limiter,
                clock,
                passwordEncoder.encode(DurableLoginService.DUMMY_CANDIDATE));
    }

    @Test
    void successfulLoginPersistsOnlyDigestAndSupportsValidationAndLogout() {
        String email = "login-" + UUID.randomUUID() + "@example.test";
        UUID userId = insertAccount(email, "ACTIVE", true, passwordEncoder.encode(PASSWORD));

        DurableLoginService.LoginAttempt attempt = loginService.login("  " + email.toUpperCase() + "  ", PASSWORD);

        assertThat(attempt.status()).isEqualTo(DurableLoginService.Status.AUTHENTICATED);
        assertThat(attempt.session()).isNotNull();
        String rawToken = attempt.session().token();
        assertThat(rawToken).matches("[A-Za-z0-9_-]{43}");
        assertThat(attempt.session().expiresAt())
                .isEqualTo(attempt.session().issuedAt().plus(SessionLifetimePolicy.MAX_SESSION_LIFETIME));
        assertThat(attempt.session().toString()).doesNotContain(rawToken).contains("[REDACTED]");

        String expectedDigest = tokenCodec.hashToken(rawToken);
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM neurosql_app.user_sessions WHERE user_id = ?",
                String.class,
                userId)).isEqualTo(expectedDigest);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM neurosql_app.user_sessions WHERE token_hash = ?",
                Integer.class,
                rawToken)).isZero();

        JdbcSessionRepository.StoredSession stored = sessions.validateSession(rawToken, clock.instant()).orElseThrow();
        assertThat(stored.userId()).isEqualTo(userId);
        assertThat(stored.expiresAt()).isEqualTo(attempt.session().expiresAt());

        assertThat(sessions.revokeSession(rawToken, clock.instant())).isTrue();
        assertThat(sessions.validateSession(rawToken, clock.instant())).isEmpty();
    }

    @Test
    void unverifiedAndInactiveAccountsCannotReceiveSessions() {
        String suffix = UUID.randomUUID().toString();
        UUID unverifiedUser = insertAccount(
                "unverified-" + suffix + "@example.test", "ACTIVE", false, passwordEncoder.encode(PASSWORD));
        UUID inactiveUser = insertAccount(
                "inactive-" + suffix + "@example.test", "DISABLED", true, passwordEncoder.encode(PASSWORD));

        assertThat(loginService.login("unverified-" + suffix + "@example.test", PASSWORD).status())
                .isEqualTo(DurableLoginService.Status.INVALID_CREDENTIALS);
        assertThat(loginService.login("inactive-" + suffix + "@example.test", PASSWORD).status())
                .isEqualTo(DurableLoginService.Status.INVALID_CREDENTIALS);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM neurosql_app.user_sessions WHERE user_id IN (?, ?)",
                Integer.class,
                unverifiedUser,
                inactiveUser)).isZero();
    }

    private UUID insertAccount(String email, String status, boolean verified, String passwordHash) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO neurosql_app.app_users
                    (user_id, status, email, password_hash, display_name, email_verified_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                userId,
                status,
                email,
                passwordHash,
                "NeuroSQL integration test",
                verified ? Timestamp.from(clock.instant()) : null);
        return userId;
    }
}
