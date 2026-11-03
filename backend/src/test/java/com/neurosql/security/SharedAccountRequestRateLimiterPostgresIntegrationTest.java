package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises the shared account-request quotas through independent limiter instances
 * connected to the same disposable PostgreSQL database. This test is deliberately
 * restricted to the local CI database and must never target Neon or another project.
 */
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
class SharedAccountRequestRateLimiterPostgresIntegrationTest {
    private static final String HMAC_KEY = "ci-only-shared-account-rate-limit-hmac-key-32";
    private static final String LIFECYCLE_EMAIL_PREFIX = "neurosql:account-lifecycle:email:";
    private static final String LIFECYCLE_IP_PREFIX = "neurosql:account-lifecycle:ip:";
    private static final String VERIFICATION_IP_PREFIX =
            "neurosql:account-lifecycle:verification-consume:ip:";
    private static final String AI_SQL_USER_PREFIX = "neurosql:ai-sql-generation:user:";

    private JdbcTemplate adminJdbc;
    private SharedAccountRequestRateLimiter firstInstance;
    private SharedAccountRequestRateLimiter secondInstance;
    private LoginIdentityHasher hasher;
    private final List<String> identities = new ArrayList<>();

    @BeforeEach
    void setUpDisposablePostgresFixture() {
        String url = requiredEnvironment("NEUROSQL_TEST_JDBC_URL");
        String username = requiredEnvironment("NEUROSQL_TEST_DB_USERNAME");
        String password = requiredEnvironment("NEUROSQL_TEST_DB_PASSWORD");

        DriverManagerDataSource adminDataSource = dataSource(url, username, password);
        adminJdbc = new JdbcTemplate(adminDataSource);
        String database = adminJdbc.queryForObject("SELECT current_database()", String.class);
        if (!"neurosql_test".equals(database)) {
            throw new IllegalStateException("Shared account-rate-limit tests only permit neurosql_test.");
        }

        adminJdbc.execute("CREATE SCHEMA IF NOT EXISTS neurosql_app");
        adminJdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.auth_login_rate_limits (
                    identity_hash VARCHAR(64) PRIMARY KEY
                        CHECK (identity_hash ~ '^[0-9a-f]{64}
                """);

        firstInstance = limiter(dataSource(url, username, password));
        secondInstance = limiter(dataSource(url, username, password));
        hasher = new LoginIdentityHasher(HMAC_KEY);
    }

    @AfterEach
    void removeOnlyRowsCreatedByThisTest() {
        if (adminJdbc == null || hasher == null) {
            return;
        }
        for (String identity : identities) {
            adminJdbc.update(
                    "DELETE FROM neurosql_app.auth_login_rate_limits WHERE identity_hash = ?",
                    hasher.hash(identity));
        }
        identities.clear();
    }

    @Test
    void emailQuotaIsSharedAcrossInstancesAndRegistrationResendPurposesAreSeparate() {
        String email = "shared-" + UUID.randomUUID() + "@example.test";
        String registrationIdentity = LIFECYCLE_EMAIL_PREFIX + "registration:" + email;
        identities.add(registrationIdentity);
        identities.add(LIFECYCLE_EMAIL_PREFIX + "verification-resend:" + email);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(firstInstance.acquireRegistration(email, null).permitted()).isTrue();
        }
        assertThat(secondInstance.acquireRegistration(email, null).permitted()).isFalse();

        // Exhausting registration must not consume the verification-resend email bucket.
        assertThat(secondInstance.acquireVerificationResend(email, null).permitted()).isTrue();
        assertThat(failureCount(registrationIdentity)).isEqualTo(5);
        assertThat(blocked(registrationIdentity)).isTrue();
    }

    @Test
    void lifecycleIpQuotaIsSharedAcrossRegistrationAndResendAndAllowsThirtyAttempts() {
        String ip = "203.0.113.40";
        String ipIdentity = LIFECYCLE_IP_PREFIX + ip;
        identities.add(ipIdentity);

        for (int attempt = 0; attempt < 29; attempt++) {
            String email = "person-" + attempt + "-" + UUID.randomUUID() + "@example.test";
            identities.add(LIFECYCLE_EMAIL_PREFIX
                    + (attempt % 2 == 0 ? "registration:" : "verification-resend:") + email);
            SharedAccountRequestRateLimiter.Decision decision = attempt % 2 == 0
                    ? firstInstance.acquireRegistration(email, ip)
                    : secondInstance.acquireVerificationResend(email, ip);
            assertThat(decision.permitted()).isTrue();
        }

        String finalEmail = "final-" + UUID.randomUUID() + "@example.test";
        identities.add(LIFECYCLE_EMAIL_PREFIX + "registration:" + finalEmail);
        assertThat(firstInstance.acquireRegistration(finalEmail, ip).permitted()).isFalse();
        assertThat(failureCount(ipIdentity)).isEqualTo(30);
        assertThat(blocked(ipIdentity)).isTrue();

        String laterEmail = "later-" + UUID.randomUUID() + "@example.test";
        identities.add(LIFECYCLE_EMAIL_PREFIX + "verification-resend:" + laterEmail);
        assertThat(secondInstance.acquireVerificationResend(laterEmail, ip).permitted()).isFalse();
    }

    @Test
    void concurrentRequestsAcrossInstancesCannotExceedTheSharedIpCapacity() throws Exception {
        String ip = "203.0.113.41";
        String ipIdentity = LIFECYCLE_IP_PREFIX + ip;
        identities.add(ipIdentity);

        int requestCount = 40;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SharedAccountRequestRateLimiter.Decision>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                int requestIndex = index;
                String email = "parallel-" + requestIndex + "-" + UUID.randomUUID() + "@example.test";
                String purpose = requestIndex % 2 == 0 ? "registration:" : "verification-resend:";
                identities.add(LIFECYCLE_EMAIL_PREFIX + purpose + email);
                SharedAccountRequestRateLimiter selected = requestIndex % 2 == 0 ? firstInstance : secondInstance;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not receive its start signal.");
                    }
                    return requestIndex % 2 == 0
                            ? selected.acquireRegistration(email, ip)
                            : selected.acquireVerificationResend(email, ip);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long permitted = 0;
            for (Future<SharedAccountRequestRateLimiter.Decision> future : futures) {
                if (future.get(30, TimeUnit.SECONDS).permitted()) {
                    permitted++;
                }
            }

            assertThat(permitted).isLessThanOrEqualTo(29);
            assertThat(failureCount(ipIdentity)).isEqualTo(30);
            assertThat(blocked(ipIdentity)).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verificationTokenConsumptionUsesItsOwnSharedIpBucket() {
        String ip = "203.0.113.42";
        String identity = VERIFICATION_IP_PREFIX + ip;
        identities.add(identity);

        for (int attempt = 0; attempt < 29; attempt++) {
            assertThat(firstInstance.acquireVerificationConsumption(ip).permitted()).isTrue();
        }
        assertThat(secondInstance.acquireVerificationConsumption(ip).permitted()).isFalse();
        assertThat(failureCount(identity)).isEqualTo(30);
        assertThat(blocked(identity)).isTrue();

        // Verification-token attempts do not consume the registration/resend IP bucket.
        String email = "unrelated-" + UUID.randomUUID() + "@example.test";
        identities.add(LIFECYCLE_EMAIL_PREFIX + "registration:" + email);
        assertThat(firstInstance.acquireRegistration(email, ip).permitted()).isTrue();
    }

    @Test
    void aiSqlQuotaIsSharedAcrossInstancesAndSeparateFromAccountLifecycle() {
        UUID userId = UUID.randomUUID();
        String aiIdentity = AI_SQL_USER_PREFIX + userId;
        identities.add(aiIdentity);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(firstInstance.acquireAiSqlGeneration(userId).permitted()).isTrue();
        }
        assertThat(secondInstance.acquireAiSqlGeneration(userId).permitted()).isFalse();
        assertThat(failureCount(aiIdentity)).isEqualTo(5);
        assertThat(blocked(aiIdentity)).isTrue();

        String email = "unrelated-" + UUID.randomUUID() + "@example.test";
        identities.add(LIFECYCLE_EMAIL_PREFIX + "registration:" + email);
        assertThat(secondInstance.acquireRegistration(email, null).permitted()).isTrue();
    }

    @Test
    void unavailableDatabaseFailsClosedAtTheSharedLimiterBoundary() {
        DriverManagerDataSource brokenDataSource =
                dataSource("jdbc:postgresql://127.0.0.1:1/neurosql_test?connectTimeout=1", "postgres", "postgres");
        SharedAccountRequestRateLimiter unavailable = limiter(brokenDataSource);

        assertThatThrownBy(() -> unavailable.acquireRegistration(
                "unavailable-" + UUID.randomUUID() + "@example.test", null))
                .isInstanceOf(DataAccessException.class);
    }

    private int failureCount(String identity) {
        return adminJdbc.queryForObject(
                "SELECT cardinality(failure_timestamps) FROM neurosql_app.auth_login_rate_limits WHERE identity_hash = ?",
                Integer.class,
                hasher.hash(identity));
    }

    private boolean blocked(String identity) {
        return Boolean.TRUE.equals(adminJdbc.queryForObject(
                "SELECT blocked_until > now() FROM neurosql_app.auth_login_rate_limits WHERE identity_hash = ?",
                Boolean.class,
                hasher.hash(identity)));
    }

    private static SharedAccountRequestRateLimiter limiter(DriverManagerDataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new SharedAccountRequestRateLimiter(new JdbcLoginAttemptRateLimiter(
                jdbc, new DataSourceTransactionManager(dataSource), HMAC_KEY));
    }

    private static DriverManagerDataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the disposable PostgreSQL integration test.");
        }
        return value;
    }
}
),
                    failure_timestamps TIMESTAMPTZ[] NOT NULL DEFAULT ARRAY[]::TIMESTAMPTZ[],
                    blocked_until TIMESTAMPTZ,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CONSTRAINT auth_login_rate_limits_failure_timestamps_check
                        CHECK (cardinality(failure_timestamps) <= 30),
                    CONSTRAINT auth_login_rate_limits_failure_timestamps_no_null_check
                        CHECK (array_position(failure_timestamps, NULL) IS NULL),
                    CONSTRAINT auth_login_rate_limit_block_consistency CHECK (
                        (blocked_until IS NULL AND cardinality(failure_timestamps) < 30)
                        OR (blocked_until IS NOT NULL
                            AND cardinality(failure_timestamps) BETWEEN 5 AND 30)
                    )
                )
                """);

        firstInstance = limiter(dataSource(url, username, password));
        secondInstance = limiter(dataSource(url, username, password));
        hasher = new LoginIdentityHasher(HMAC_KEY);
    }

    @AfterEach
    void removeOnlyRowsCreatedByThisTest() {
        if (adminJdbc == null || hasher == null) {
            return;
        }
        for (String identity : identities) {
            adminJdbc.update(
                    "DELETE FROM neurosql_app.auth_login_rate_limits WHERE identity_hash = ?",
                    hasher.hash(identity));
        }
        identities.clear();
    }

    @Test
    void emailQuotaIsSharedAcrossInstancesAndRegistrationResendPurposesAreSeparate() {
        String email = "shared-" + UUID.randomUUID() + "@example.test";
        String registrationIdentity = LIFECYCLE_EMAIL_PREFIX + "registration:" + email;
        identities.add(registrationIdentity);
        identities.add(LIFECYCLE_EMAIL_PREFIX + "verification-resend:" + email);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(firstInstance.acquireRegistration(email, null).permitted()).isTrue();
        }
        assertThat(secondInstance.acquireRegistration(email, null).permitted()).isFalse();

        // Exhausting registration must not consume the verification-resend email bucket.
        assertThat(secondInstance.acquireVerificationResend(email, null).permitted()).isTrue();
        assertThat(failureCount(registrationIdentity)).isEqualTo(5);
        assertThat(blocked(registrationIdentity)).isTrue();
    }

    @Test
    void lifecycleIpQuotaIsSharedAcrossRegistrationAndResendAndAllowsThirtyAttempts() {
        String ip = "203.0.113.40";
        String ipIdentity = LIFECYCLE_IP_PREFIX + ip;
        identities.add(ipIdentity);

        for (int attempt = 0; attempt < 29; attempt++) {
            String email = "person-" + attempt + "-" + UUID.randomUUID() + "@example.test";
            identities.add(LIFECYCLE_EMAIL_PREFIX
                    + (attempt % 2 == 0 ? "registration:" : "verification-resend:") + email);
            SharedAccountRequestRateLimiter.Decision decision = attempt % 2 == 0
                    ? firstInstance.acquireRegistration(email, ip)
                    : secondInstance.acquireVerificationResend(email, ip);
            assertThat(decision.permitted()).isTrue();
        }

        String finalEmail = "final-" + UUID.randomUUID() + "@example.test";
        identities.add(LIFECYCLE_EMAIL_PREFIX + "registration:" + finalEmail);
        assertThat(firstInstance.acquireRegistration(finalEmail, ip).permitted()).isFalse();
        assertThat(failureCount(ipIdentity)).isEqualTo(30);
        assertThat(blocked(ipIdentity)).isTrue();

        String laterEmail = "later-" + UUID.randomUUID() + "@example.test";
        identities.add(LIFECYCLE_EMAIL_PREFIX + "verification-resend:" + laterEmail);
        assertThat(secondInstance.acquireVerificationResend(laterEmail, ip).permitted()).isFalse();
    }

    @Test
    void concurrentRequestsAcrossInstancesCannotExceedTheSharedIpCapacity() throws Exception {
        String ip = "203.0.113.41";
        String ipIdentity = LIFECYCLE_IP_PREFIX + ip;
        identities.add(ipIdentity);

        int requestCount = 40;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SharedAccountRequestRateLimiter.Decision>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                int requestIndex = index;
                String email = "parallel-" + requestIndex + "-" + UUID.randomUUID() + "@example.test";
                String purpose = requestIndex % 2 == 0 ? "registration:" : "verification-resend:";
                identities.add(LIFECYCLE_EMAIL_PREFIX + purpose + email);
                SharedAccountRequestRateLimiter selected = requestIndex % 2 == 0 ? firstInstance : secondInstance;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test did not receive its start signal.");
                    }
                    return requestIndex % 2 == 0
                            ? selected.acquireRegistration(email, ip)
                            : selected.acquireVerificationResend(email, ip);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long permitted = 0;
            for (Future<SharedAccountRequestRateLimiter.Decision> future : futures) {
                if (future.get(30, TimeUnit.SECONDS).permitted()) {
                    permitted++;
                }
            }

            assertThat(permitted).isLessThanOrEqualTo(29);
            assertThat(failureCount(ipIdentity)).isEqualTo(30);
            assertThat(blocked(ipIdentity)).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verificationTokenConsumptionUsesItsOwnSharedIpBucket() {
        String ip = "203.0.113.42";
        String identity = VERIFICATION_IP_PREFIX + ip;
        identities.add(identity);

        for (int attempt = 0; attempt < 29; attempt++) {
            assertThat(firstInstance.acquireVerificationConsumption(ip).permitted()).isTrue();
        }
        assertThat(secondInstance.acquireVerificationConsumption(ip).permitted()).isFalse();
        assertThat(failureCount(identity)).isEqualTo(30);
        assertThat(blocked(identity)).isTrue();

        // Verification-token attempts do not consume the registration/resend IP bucket.
        String email = "unrelated-" + UUID.randomUUID() + "@example.test";
        identities.add(LIFECYCLE_EMAIL_PREFIX + "registration:" + email);
        assertThat(firstInstance.acquireRegistration(email, ip).permitted()).isTrue();
    }

    @Test
    void aiSqlQuotaIsSharedAcrossInstancesAndSeparateFromAccountLifecycle() {
        UUID userId = UUID.randomUUID();
        String aiIdentity = AI_SQL_USER_PREFIX + userId;
        identities.add(aiIdentity);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(firstInstance.acquireAiSqlGeneration(userId).permitted()).isTrue();
        }
        assertThat(secondInstance.acquireAiSqlGeneration(userId).permitted()).isFalse();
        assertThat(failureCount(aiIdentity)).isEqualTo(5);
        assertThat(blocked(aiIdentity)).isTrue();

        String email = "unrelated-" + UUID.randomUUID() + "@example.test";
        identities.add(LIFECYCLE_EMAIL_PREFIX + "registration:" + email);
        assertThat(secondInstance.acquireRegistration(email, null).permitted()).isTrue();
    }

    @Test
    void unavailableDatabaseFailsClosedAtTheSharedLimiterBoundary() {
        DriverManagerDataSource brokenDataSource =
                dataSource("jdbc:postgresql://127.0.0.1:1/neurosql_test?connectTimeout=1", "postgres", "postgres");
        SharedAccountRequestRateLimiter unavailable = limiter(brokenDataSource);

        assertThatThrownBy(() -> unavailable.acquireRegistration(
                "unavailable-" + UUID.randomUUID() + "@example.test", null))
                .isInstanceOf(DataAccessException.class);
    }

    private int failureCount(String identity) {
        return adminJdbc.queryForObject(
                "SELECT cardinality(failure_timestamps) FROM neurosql_app.auth_login_rate_limits WHERE identity_hash = ?",
                Integer.class,
                hasher.hash(identity));
    }

    private boolean blocked(String identity) {
        return Boolean.TRUE.equals(adminJdbc.queryForObject(
                "SELECT blocked_until > now() FROM neurosql_app.auth_login_rate_limits WHERE identity_hash = ?",
                Boolean.class,
                hasher.hash(identity)));
    }

    private static SharedAccountRequestRateLimiter limiter(DriverManagerDataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new SharedAccountRequestRateLimiter(new JdbcLoginAttemptRateLimiter(
                jdbc, new DataSourceTransactionManager(dataSource), HMAC_KEY));
    }

    private static DriverManagerDataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the disposable PostgreSQL integration test.");
        }
        return value;
    }
}
