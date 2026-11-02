package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PasswordResetServicePostgresIntegrationTest.TestConfig.class)
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
class PasswordResetServicePostgresIntegrationTest {
    private static final String OLD_PASSWORD = "Old-Correct-Horse-2026";
    private static final String NEW_PASSWORD = "New-Correct-Horse-2026";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private AccountActionTokenService actionTokens;

    @Autowired
    private JdbcSessionRepository sessions;

    @Autowired
    private TestSessionRepository testSessions;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CoordinatedPasswordEncoder coordinatedPasswordEncoder;

    @Autowired
    private DurableLoginService durableLoginService;

    @Autowired
    private SessionLifecycleService sessionLifecycleService;

    private final SessionTokenCodec tokenCodec = new SessionTokenCodec();

    @BeforeEach
    void createNonDestructiveDisposableFixture() {
        testSessions.failAfterRevocation = false;
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS neurosql_app");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.app_users (
                    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                    email VARCHAR(320),
                    password_hash VARCHAR(255),
                    display_name VARCHAR(120),
                    email_verified_at TIMESTAMPTZ,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS email VARCHAR(320)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS display_name VARCHAR(120)");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ");
        jdbc.execute("ALTER TABLE neurosql_app.app_users ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now()");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.account_action_tokens (
                    token_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id UUID NOT NULL REFERENCES neurosql_app.app_users(user_id) ON DELETE CASCADE,
                    purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
                    token_hash VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    expires_at TIMESTAMPTZ NOT NULL,
                    consumed_at TIMESTAMPTZ,
                    CHECK (expires_at > created_at),
                    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
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
    }

    @Test
    void resetChangesBcryptHashConsumesTokenAndRevokesAllSessions() {
        assertThat(AopUtils.isAopProxy(passwordResetService)).isTrue();
        Fixture fixture = insertUser("ACTIVE", true);
        insertSessions(fixture.userId());
        AccountActionTokenService.IssuedActionToken token =
                actionTokens.issueToken(fixture.userId(), AccountActionTokenService.Purpose.PASSWORD_RESET);

        PasswordResetService.ResetResult result = passwordResetService.resetPassword(token.token(), NEW_PASSWORD);

        assertThat(result.successful()).isTrue();
        assertThat(result.revokedSessions()).isEqualTo(2);
        String newHash = jdbc.queryForObject(
                "SELECT password_hash FROM neurosql_app.app_users WHERE user_id = ?",
                String.class,
                fixture.userId());
        assertThat(passwordEncoder.matches(NEW_PASSWORD, newHash)).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, newHash)).isFalse();
        assertThat(tokenConsumed(token.token())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.user_sessions WHERE user_id = ? AND revoked_at IS NOT NULL",
                Integer.class,
                fixture.userId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.user_sessions WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                fixture.userId())).isZero();

        PasswordResetService.ResetResult replay =
                passwordResetService.resetPassword(token.token(), "Another-Correct-Password-2026");
        assertThat(replay.successful()).isFalse();
        assertThat(replay.revokedSessions()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM neurosql_app.app_users WHERE user_id = ?",
                String.class,
                fixture.userId())).isEqualTo(newHash);
    }

    @Test
    void expiredAndWrongPurposeTokensCannotChangePasswordOrRevokeSessions() {
        Fixture fixture = insertUser("ACTIVE", true);
        insertSessions(fixture.userId());
        AccountActionTokenService.IssuedActionToken expired =
                actionTokens.issueToken(fixture.userId(), AccountActionTokenService.Purpose.PASSWORD_RESET);
        jdbc.update("""
                UPDATE neurosql_app.account_action_tokens
                SET created_at = now() - INTERVAL '2 hours',
                    expires_at = now() - INTERVAL '1 hour'
                WHERE token_hash = ?
                """,
                tokenCodec.hashToken(expired.token()));

        PasswordResetService.ResetResult expiredResult =
                passwordResetService.resetPassword(expired.token(), NEW_PASSWORD);
        assertThat(expiredResult.successful()).isFalse();
        assertThat(tokenConsumed(expired.token())).isFalse();

        AccountActionTokenService.IssuedActionToken wrongPurpose =
                actionTokens.issueToken(fixture.userId(), AccountActionTokenService.Purpose.EMAIL_VERIFICATION);
        PasswordResetService.ResetResult wrongPurposeResult =
                passwordResetService.resetPassword(wrongPurpose.token(), NEW_PASSWORD);
        assertThat(wrongPurposeResult.successful()).isFalse();
        assertThat(tokenConsumed(wrongPurpose.token())).isFalse();

        assertUnchanged(fixture);
    }

    @Test
    void inactiveAccountFailureRollsBackTokenConsumption() {
        Fixture fixture = insertUser("DISABLED", true);
        AccountActionTokenService.IssuedActionToken token =
                actionTokens.issueToken(fixture.userId(), AccountActionTokenService.Purpose.PASSWORD_RESET);

        assertThatThrownBy(() -> passwordResetService.resetPassword(token.token(), NEW_PASSWORD))
                .isInstanceOf(IllegalStateException.class);

        assertThat(tokenConsumed(token.token())).isFalse();
        assertThat(currentPasswordHash(fixture.userId())).isEqualTo(fixture.passwordHash());
    }

    @Test
    void sessionRevocationFailureRollsBackTokenAndPasswordUpdates() {
        Fixture fixture = insertUser("ACTIVE", true);
        insertSessions(fixture.userId());
        AccountActionTokenService.IssuedActionToken token =
                actionTokens.issueToken(fixture.userId(), AccountActionTokenService.Purpose.PASSWORD_RESET);
        testSessions.failAfterRevocation = true;

        assertThatThrownBy(() -> passwordResetService.resetPassword(token.token(), NEW_PASSWORD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated session-revocation failure");

        assertThat(tokenConsumed(token.token())).isFalse();
        assertThat(currentPasswordHash(fixture.userId())).isEqualTo(fixture.passwordHash());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.user_sessions WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                fixture.userId())).isEqualTo(2);
    }

    @Test
    void concurrentLoginCannotLeaveAnOldPasswordSessionAfterResetCommits() throws Exception {
        assertThat(AopUtils.isAopProxy(durableLoginService)).isTrue();
        Fixture fixture = insertUser("ACTIVE", true);
        AccountActionTokenService.IssuedActionToken resetToken =
                actionTokens.issueToken(fixture.userId(), AccountActionTokenService.Purpose.PASSWORD_RESET);

        CoordinatedPasswordEncoder.MatchGate gate = coordinatedPasswordEncoder.blockNextMatch();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DurableLoginService.LoginAttempt> loginFuture =
                    executor.submit(() -> durableLoginService.login(fixture.email(), OLD_PASSWORD));
            assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch resetStarted = new CountDownLatch(1);
            Future<PasswordResetService.ResetResult> resetFuture = executor.submit(() -> {
                resetStarted.countDown();
                return passwordResetService.resetPassword(resetToken.token(), NEW_PASSWORD);
            });
            assertThat(resetStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // The reset must be waiting on the account row held by the login transaction.
            assertThat(awaitPasswordUpdateWaitingOnAccountLock(resetFuture, Duration.ofSeconds(5))).isTrue();

            gate.release.countDown();
            DurableLoginService.LoginAttempt login = loginFuture.get(10, TimeUnit.SECONDS);
            PasswordResetService.ResetResult reset = resetFuture.get(10, TimeUnit.SECONDS);

            assertThat(login.status()).isEqualTo(DurableLoginService.Status.AUTHENTICATED);
            assertThat(reset.successful()).isTrue();
            assertThat(passwordEncoder.matches(NEW_PASSWORD, currentPasswordHash(fixture.userId()))).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM neurosql_app.user_sessions WHERE user_id = ? AND revoked_at IS NULL",
                    Integer.class,
                    fixture.userId())).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM neurosql_app.user_sessions WHERE user_id = ? AND revoked_at IS NOT NULL",
                    Integer.class,
                    fixture.userId())).isEqualTo(1);
            assertThat(sessionLifecycleService.validateSession(login.session().token(), Instant.now())).isEmpty();

            // Once reset commits, the old password must not be able to create another session.
            assertThat(durableLoginService.login(fixture.email(), OLD_PASSWORD).status())
                    .isEqualTo(DurableLoginService.Status.INVALID_CREDENTIALS);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM neurosql_app.user_sessions WHERE user_id = ? AND revoked_at IS NULL",
                    Integer.class,
                    fixture.userId())).isZero();
        } finally {
            gate.release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private boolean awaitPasswordUpdateWaitingOnAccountLock(
            Future<?> resetFuture, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (resetFuture.isDone()) {
                return false;
            }
            Boolean waiting = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_stat_activity
                        WHERE pid <> pg_backend_pid()
                          AND datname = current_database()
                          AND wait_event_type = 'Lock'
                          AND ltrim(query) LIKE 'UPDATE neurosql_app.app_users%'
                    )
                    """, Boolean.class);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private Fixture insertUser(String status, boolean verified) {
        UUID userId = UUID.randomUUID();
        String email = "password-reset-" + userId + "@example.test";
        String hash = passwordEncoder.encode(OLD_PASSWORD);
        jdbc.update("""
                INSERT INTO neurosql_app.app_users
                    (user_id, status, email, password_hash, display_name, email_verified_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                userId,
                status,
                email,
                hash,
                "Password Reset Test",
                verified ? Timestamp.from(Instant.now()) : null);
        return new Fixture(userId, email, hash);
    }

    private void insertSessions(UUID userId) {
        Instant issuedAt = Instant.now().minusSeconds(60);
        sessions.createSession(
                userId,
                tokenCodec.hashToken(tokenCodec.issueToken()),
                issuedAt,
                issuedAt.plusSeconds(3600));
        sessions.createSession(
                userId,
                tokenCodec.hashToken(tokenCodec.issueToken()),
                issuedAt,
                issuedAt.plusSeconds(7200));
    }

    private boolean tokenConsumed(String rawToken) {
        return jdbc.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM neurosql_app.account_action_tokens WHERE token_hash = ?",
                Boolean.class,
                tokenCodec.hashToken(rawToken));
    }

    private String currentPasswordHash(UUID userId) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM neurosql_app.app_users WHERE user_id = ?",
                String.class,
                userId);
    }

    private void assertUnchanged(Fixture fixture) {
        assertThat(currentPasswordHash(fixture.userId())).isEqualTo(fixture.passwordHash());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.user_sessions WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                fixture.userId())).isEqualTo(2);
    }

    private record Fixture(UUID userId, String email, String passwordHash) {
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DriverManagerDataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.postgresql.Driver");
            dataSource.setUrl(System.getenv("NEUROSQL_TEST_JDBC_URL"));
            dataSource.setUsername(System.getenv("NEUROSQL_TEST_DB_USERNAME"));
            dataSource.setPassword(System.getenv("NEUROSQL_TEST_DB_PASSWORD"));
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DriverManagerDataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DriverManagerDataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        CoordinatedPasswordEncoder passwordEncoder() {
            return new CoordinatedPasswordEncoder();
        }

        @Bean
        JdbcAccountRepository accountRepository(JdbcTemplate jdbc) {
            return new JdbcAccountRepository(jdbc);
        }

        @Bean
        JdbcAccountActionTokenRepository actionTokenRepository(JdbcTemplate jdbc) {
            return new JdbcAccountActionTokenRepository(jdbc);
        }

        @Bean
        AccountActionTokenService actionTokens(JdbcAccountActionTokenRepository repository) {
            return new AccountActionTokenService(repository);
        }

        @Bean
        TestSessionRepository sessionRepository(JdbcTemplate jdbc) {
            return new TestSessionRepository(jdbc);
        }

        @Bean
        SessionLifecycleService sessionLifecycleService(JdbcSessionRepository sessions) {
            return new SessionLifecycleService(sessions);
        }

        @Bean
        BasicAuthAttemptRateLimiter loginAttemptRateLimiter() {
            return new BasicAuthAttemptRateLimiter(Clock.systemUTC());
        }

        @Bean
        DurableLoginService durableLoginService(
                JdbcAccountRepository accounts,
                PasswordEncoder passwordEncoder,
                SessionLifecycleService sessions,
                BasicAuthAttemptRateLimiter limiter) {
            return new DurableLoginService(
                    accounts,
                    passwordEncoder,
                    sessions,
                    limiter,
                    Clock.systemUTC(),
                    passwordEncoder.encode(DurableLoginService.DUMMY_CANDIDATE));
        }

        @Bean
        PasswordResetService passwordResetService(
                AccountActionTokenService actionTokens,
                JdbcAccountRepository accounts,
                JdbcSessionRepository sessions,
                PasswordEncoder passwordEncoder) {
            return new PasswordResetService(actionTokens, accounts, sessions, passwordEncoder);
        }
    }

    static final class CoordinatedPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder(4);
        private final AtomicReference<MatchGate> nextMatchGate = new AtomicReference<>();

        MatchGate blockNextMatch() {
            MatchGate gate = new MatchGate();
            nextMatchGate.set(gate);
            return gate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            MatchGate gate = nextMatchGate.getAndSet(null);
            if (gate != null) {
                gate.entered.countDown();
                try {
                    if (!gate.release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the coordinated password match.");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Coordinated password matching was interrupted.", interrupted);
                }
            }
            return delegate.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return delegate.upgradeEncoding(encodedPassword);
        }

        static final class MatchGate {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
        }
    }

    static class TestSessionRepository extends JdbcSessionRepository {
        private volatile boolean failAfterRevocation;

        TestSessionRepository(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public int revokeAllForUser(UUID userId) {
            int revoked = super.revokeAllForUser(userId);
            if (failAfterRevocation) {
                failAfterRevocation = false;
                throw new IllegalStateException("simulated session-revocation failure");
            }
            return revoked;
        }
    }
}
