package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Exercises bounded SQL execution with a real restricted PostgreSQL login.
 * The environment guard permits only the disposable loopback neurosql_test
 * database used by CI; this fixture must never target Neon or production.
 */
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
class ReadOnlyQueryExecutorPostgresIntegrationTest {
    private static final int MAX_ROWS = 25;

    private static JdbcTemplate adminJdbc;
    private static JdbcTemplate runtimeJdbc;
    private static String expectedDatabase;
    private static String schemaName;
    private static String readOnlyRole;
    private static ReadOnlySqlValidator validator;

    @BeforeAll
    static void createDisposableReadOnlyLogin() {
        String url = requiredEnvironment("NEUROSQL_TEST_JDBC_URL");
        adminJdbc = jdbc(url,
                requiredEnvironment("NEUROSQL_TEST_DB_USERNAME"),
                requiredEnvironment("NEUROSQL_TEST_DB_PASSWORD"));
        expectedDatabase = adminJdbc.queryForObject("SELECT current_database()", String.class);
        if (!"neurosql_test".equals(expectedDatabase)) {
            throw new IllegalStateException("SQL execution integration tests only permit neurosql_test.");
        }

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        schemaName = "neurosql_sql_probe_" + suffix;
        readOnlyRole = "neurosql_sql_readonly_" + suffix;
        String loginRole = "neurosql_sql_login_" + suffix;
        String password = "ci-" + UUID.randomUUID().toString().replace("-", "");

        adminJdbc.execute("CREATE ROLE " + readOnlyRole
                + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        adminJdbc.execute("CREATE ROLE " + loginRole + " LOGIN PASSWORD '" + password
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        adminJdbc.execute("GRANT " + readOnlyRole + " TO " + loginRole);

        adminJdbc.execute("CREATE SCHEMA " + schemaName);
        adminJdbc.execute("CREATE TABLE " + schemaName
                + ".probe (id INTEGER PRIMARY KEY, label TEXT NOT NULL)");
        adminJdbc.execute("INSERT INTO " + schemaName
                + ".probe (id, label) SELECT value, 'row-' || value FROM generate_series(1, 55) AS series(value)");
        adminJdbc.execute("CREATE FUNCTION " + schemaName + ".attempt_write() RETURNS INTEGER "
                + "LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, " + schemaName
                + " AS $$ BEGIN INSERT INTO " + schemaName
                + ".probe (id, label) VALUES (999, 'must-not-commit'); RETURN 1; END $$");

        adminJdbc.execute("GRANT USAGE ON SCHEMA " + schemaName + " TO " + readOnlyRole);
        adminJdbc.execute("GRANT SELECT ON " + schemaName + ".probe TO " + readOnlyRole);
        adminJdbc.execute("GRANT EXECUTE ON FUNCTION " + schemaName
                + ".attempt_write() TO " + readOnlyRole);

        runtimeJdbc = jdbc(url, loginRole, password);
        validator = new ReadOnlySqlValidator();
    }

    @Test
    void executesSelectAndCapsRowsAndCellMetadata() {
        ReadOnlyQueryExecutor.QueryResponse result = runtimeExecutor().execute(
                "SELECT id, label FROM " + schemaName + ".probe ORDER BY id");

        assertThat(result.columns()).extracting(ReadOnlyQueryExecutor.ColumnInfo::name)
                .containsExactly("id", "label");
        assertThat(result.rowCount()).isEqualTo(MAX_ROWS);
        assertThat(result.rows()).hasSize(MAX_ROWS);
        assertThat(result.truncated()).isTrue();
        assertThat(result.rows().get(0)).containsExactly("1", "row-1");
    }

    @Test
    void returnsJsonPlanWithoutExecutingTheSubmittedQuery() {
        int before = rowCount();

        ReadOnlyQueryExecutor.ExplainResponse result =
                runtimeExecutor().explain("SELECT " + schemaName + ".attempt_write()");

        assertThat(result.planJson()).contains("\\"Plan\\"");
        assertThat(result.analyze()).isFalse();
        assertThat(result.elapsedTimeMillis()).isGreaterThanOrEqualTo(0);
        // EXPLAIN without ANALYZE plans the SECURITY DEFINER call but never invokes it.
        assertThat(rowCount()).isEqualTo(before);
    }

    @Test
    void rejectsMutationBeforeGeneratingAPlan() {
        assertThatThrownBy(() -> runtimeExecutor().explain(
                "DELETE FROM " + schemaName + ".probe"))
                .isInstanceOf(ReadOnlyQueryExecutor.QueryRejectedException.class);

        assertThat(rowCount()).isEqualTo(55);
    }

    @Test
    void rejectsMutationBeforeItReachesPostgres() {
        assertThatThrownBy(() -> runtimeExecutor().execute(
                "DELETE FROM " + schemaName + ".probe"))
                .isInstanceOf(ReadOnlyQueryExecutor.QueryRejectedException.class);

        assertThat(rowCount()).isEqualTo(55);
    }

    @Test
    void postgresReadOnlyTransactionBlocksWriteInsideSecurityDefinerFunction() {
        assertThatThrownBy(() -> runtimeExecutor().execute(
                "SELECT " + schemaName + ".attempt_write()"))
                .isInstanceOf(ReadOnlyQueryExecutor.QueryExecutionException.class)
                .hasMessage("The query could not be completed.");

        assertThat(rowCount()).isEqualTo(55);
    }

    @Test
    void rejectsPostgresMaintenancePrivilegeEvenWithoutDmlGrants() {
        adminJdbc.execute("GRANT MAINTAIN ON TABLE " + schemaName + ".probe TO " + readOnlyRole);
        try {
            assertThatThrownBy(() -> runtimeExecutor().execute("SELECT 1"))
                    .isInstanceOf(ReadOnlyQueryExecutor.QueryExecutionUnavailableException.class);
        } finally {
            adminJdbc.execute("REVOKE MAINTAIN ON TABLE " + schemaName + ".probe FROM " + readOnlyRole);
        }
    }

    @Test
    void rejectsDatabaseAdministratorEvenForSelect() {
        assertThatThrownBy(() -> executor(adminJdbc).execute("SELECT 1"))
                .isInstanceOf(ReadOnlyQueryExecutor.QueryExecutionUnavailableException.class);
    }

    private static ReadOnlyQueryExecutor runtimeExecutor() {
        return executor(runtimeJdbc);
    }

    private static ReadOnlyQueryExecutor executor(JdbcTemplate jdbc) {
        PlatformTransactionManager transactionManager =
                new DataSourceTransactionManager(jdbc.getDataSource());
        return new ReadOnlyQueryExecutor(
                jdbc, transactionManager, validator, expectedDatabase, readOnlyRole, MAX_ROWS, 5);
    }

    private static int rowCount() {
        return adminJdbc.queryForObject("SELECT count(*) FROM " + schemaName + ".probe", Integer.class);
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
