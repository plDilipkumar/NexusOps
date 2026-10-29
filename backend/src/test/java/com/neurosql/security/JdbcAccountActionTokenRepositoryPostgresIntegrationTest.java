package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises account-action token SQL only against the disposable PostgreSQL 17
 * database supplied by CI. The URL guard prevents accidental use of Neon or
 * another shared database.
 */
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
class JdbcAccountActionTokenRepositoryPostgresIntegrationTest {
    private JdbcTemplate jdbc;
    private JdbcAccountActionTokenRepository repository;
    private final SessionTokenCodec codec = new SessionTokenCodec();

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
        repository = new JdbcAccountActionTokenRepository(jdbc);
    }

    @Test
    void persistsOnlyDigestAndConsumesTokenOnceForMatchingPurpose() {
        UUID userId = insertUser();
        String rawToken = codec.issueToken();
        String digest = codec.hashToken(rawToken);

        Instant expiresAt = repository.createToken(
                userId,
                AccountActionTokenService.Purpose.PASSWORD_RESET,
                digest,
                Duration.ofMinutes(30).getSeconds());

        assertThat(expiresAt).isAfter(Instant.now());
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM neurosql_app.account_action_tokens WHERE token_hash = ?",
                String.class,
                digest)).isEqualTo(digest);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.account_action_tokens WHERE token_hash = ?",
                Integer.class,
                rawToken)).isZero();

        assertThat(repository.consumeToken(digest, AccountActionTokenService.Purpose.EMAIL_VERIFICATION)).isEmpty();
        assertThat(repository.consumeToken(digest, AccountActionTokenService.Purpose.PASSWORD_RESET))
                .contains(userId);
        assertThat(repository.consumeToken(digest, AccountActionTokenService.Purpose.PASSWORD_RESET)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at IS NOT NULL FROM neurosql_app.account_action_tokens WHERE token_hash = ?",
                Boolean.class,
                digest)).isTrue();
    }

    @Test
    void refusesExpiredTokensWithoutMarkingThemConsumed() {
        UUID userId = insertUser();
        String digest = codec.hashToken(codec.issueToken());
        jdbc.update("""
                INSERT INTO neurosql_app.account_action_tokens
                    (user_id, purpose, token_hash, created_at, expires_at)
                VALUES (?, 'EMAIL_VERIFICATION', ?, now() - INTERVAL '2 hours', now() - INTERVAL '1 hour')
                """,
                userId,
                digest);

        assertThat(repository.consumeToken(digest, AccountActionTokenService.Purpose.EMAIL_VERIFICATION)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT consumed_at IS NULL FROM neurosql_app.account_action_tokens WHERE token_hash = ?",
                Boolean.class,
                digest)).isTrue();
    }

    @Test
    void concurrentConsumersCanUseATokenOnlyOnce() throws Exception {
        UUID userId = insertUser();
        String digest = codec.hashToken(codec.issueToken());
        repository.createToken(
                userId,
                AccountActionTokenService.Purpose.EMAIL_VERIFICATION,
                digest,
                Duration.ofHours(24).getSeconds());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<UUID>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent token-consume start.");
                    }
                    return repository.consumeToken(digest, AccountActionTokenService.Purpose.EMAIL_VERIFICATION);
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Optional<UUID>> results = List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.equals(Optional.of(userId))).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }


    @Test
    void rejectsLifetimeLongerThanTheSelectedPurposeAllows() {
        UUID userId = insertUser();
        String digest = codec.hashToken(codec.issueToken());

        assertThatThrownBy(() -> repository.createToken(
                userId,
                AccountActionTokenService.Purpose.PASSWORD_RESET,
                digest,
                Duration.ofMinutes(31).getSeconds()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.account_action_tokens WHERE token_hash = ?",
                Integer.class,
                digest)).isZero();
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        String email = userId + "@example.test";
        jdbc.update("""
                INSERT INTO neurosql_app.app_users
                    (user_id, status, email, password_hash, display_name, email_verified_at)
                VALUES (?, 'ACTIVE', ?, 'fixture-password-hash', 'Test User', NULL)
                """,
                userId,
                email);
        return userId;
    }
}
