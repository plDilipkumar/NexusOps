package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Verifies the readiness SQL through a real restricted PostgreSQL LOGIN
 * session on the disposable CI database. This fixture must never target Neon
 * or any shared/production database.
 */
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
class SharedLoginRateLimitHealthIndicatorPostgresIntegrationTest {
    private static JdbcTemplate adminJdbc;
    private static JdbcTemplate runtimeJdbc;
    private static String expectedDatabase;

    @BeforeAll
    static void createRestrictedLoginFixture() {
        String url = requiredEnvironment("NEUROSQL_TEST_JDBC_URL");
        String adminUsername = requiredEnvironment("NEUROSQL_TEST_DB_USERNAME");
        String adminPassword = requiredEnvironment("NEUROSQL_TEST_DB_PASSWORD");

        adminJdbc = jdbc(url, adminUsername, adminPassword);
        expectedDatabase = adminJdbc.queryForObject("SELECT current_database()", String.class);
        if (!"neurosql_test".equals(expectedDatabase)) {
            throw new IllegalStateException("The readiness integration test only permits neurosql_test.");
        }

        adminJdbc.execute("CREATE SCHEMA IF NOT EXISTS neurosql_app");
        adminJdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.auth_login_rate_limits (
                    identity_hash VARCHAR(64) PRIMARY KEY
                        CHECK (identity_hash ~ '^[0-9a-f]{64}$'),
                    failure_timestamps TIMESTAMPTZ[] NOT NULL DEFAULT ARRAY[]::TIMESTAMPTZ[]
                        CHECK (cardinality(failure_timestamps) <= 5)
                        CHECK (array_position(failure_timestamps, NULL) IS NULL),
                    blocked_until TIMESTAMPTZ,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    CHECK ((cardinality(failure_timestamps) < 5 AND blocked_until IS NULL)
                        OR (cardinality(failure_timestamps) = 5 AND blocked_until IS NOT NULL))
                )
                """);

        // The disposable CI database is fresh for this job; no Neon roles are created or changed.
        adminJdbc.execute("""
                CREATE ROLE neurosql_auth_runtime NOLOGIN
                    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
                """);
        String roleSuffix = UUID.randomUUID().toString().replace("-", "");
        String loginRole = "neurosql_health_login_" + roleSuffix;
        String password = "ci-" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE ROLE " + loginRole + " LOGIN PASSWORD '" + password
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        adminJdbc.execute("GRANT neurosql_auth_runtime TO " + loginRole);

        adminJdbc.execute("GRANT USAGE ON SCHEMA neurosql_app TO neurosql_auth_runtime");
        adminJdbc.execute("REVOKE ALL ON TABLE neurosql_app.auth_login_rate_limits FROM PUBLIC");
        adminJdbc.execute("""
                GRANT SELECT (identity_hash, failure_timestamps, blocked_until, updated_at)
                ON neurosql_app.auth_login_rate_limits TO neurosql_auth_runtime
                """);
        adminJdbc.execute("""
                GRANT INSERT (identity_hash, failure_timestamps, blocked_until, updated_at)
                ON neurosql_app.auth_login_rate_limits TO neurosql_auth_runtime
                """);
        adminJdbc.execute("""
                GRANT UPDATE (failure_timestamps, blocked_until, updated_at)
                ON neurosql_app.auth_login_rate_limits TO neurosql_auth_runtime
                """);
        adminJdbc.execute("""
                GRANT DELETE ON neurosql_app.auth_login_rate_limits TO neurosql_auth_runtime
                """);

        runtimeJdbc = jdbc(url, loginRole, password);
    }

    @Test
    void reportsUpWhenConnectedDirectlyAsTheRestrictedLoginRole() {
        assertThat(runtimeJdbc.queryForObject(
                "SELECT session_user = current_user AND current_user = session_user",
                Boolean.class)).isTrue();

        Health health = indicator(runtimeJdbc).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    void reportsDownWhenConnectedAsTheDatabaseAdministrator() {
        Health health = indicator(adminJdbc).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }

    private static SharedLoginRateLimitHealthIndicator indicator(JdbcTemplate jdbc) {
        return new SharedLoginRateLimitHealthIndicator(jdbc, true, true, expectedDatabase);
    }

    private static JdbcTemplate jdbc(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return new JdbcTemplate(dataSource);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the disposable PostgreSQL integration test.");
        }
        return value;
    }
}
