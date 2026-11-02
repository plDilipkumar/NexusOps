package com.neurosql.api;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes bounded read-only SELECT/WITH queries only after verifying that the
 * connected PostgreSQL identity is a dedicated, non-owning, non-privileged
 * member of the configured read-only role.
 *
 * <p>This is deliberately separate from the lexical validator: both the
 * PostgreSQL read-only transaction and database grants are enforced before
 * submitted SQL is executed. The feature is disabled by default.</p>
 */
public final class ReadOnlyQueryExecutor {
    private static final String ROLE_READINESS_SQL = """
            SELECT current_database() = ? AS expected_database,
                   session_user = current_user AS session_identity_matches,
                   EXISTS (
                       SELECT 1 FROM pg_roles role_info
                       WHERE role_info.rolname = current_user AND role_info.rolcanlogin
                   ) AS current_role_can_login,
                   current_user <> pg_get_userbyid(
                       (SELECT datdba FROM pg_database WHERE datname = current_database())
                   ) AS not_database_owner,
                   NOT EXISTS (
                       SELECT 1 FROM pg_roles role_info
                       WHERE role_info.rolname = current_user
                         AND (role_info.rolsuper OR role_info.rolcreatedb
                              OR role_info.rolcreaterole OR role_info.rolreplication
                              OR role_info.rolbypassrls)
                   ) AS safe_role_attributes,
                   EXISTS (
                       SELECT 1 FROM pg_roles expected_role
                       WHERE expected_role.rolname = ?
                         AND pg_has_role(current_user, expected_role.oid, 'MEMBER')
                   ) AS expected_read_only_role_membership,
                   NOT EXISTS (
                       SELECT 1 FROM pg_roles other_role
                       WHERE other_role.rolname NOT IN (current_user, ?)
                         AND pg_has_role(current_user, other_role.oid, 'MEMBER')
                   ) AS no_unexpected_role_membership,
                   NOT has_database_privilege(current_user, current_database(), 'CREATE')
                       AS database_create_denied,
                   NOT EXISTS (
                       SELECT 1
                       FROM pg_namespace namespace_info
                       WHERE namespace_info.nspname <> 'information_schema'
                         AND namespace_info.nspname NOT LIKE 'pg_%'
                         AND has_schema_privilege(current_user, namespace_info.oid, 'CREATE')
                   ) AS schema_create_denied,
                   NOT EXISTS (
                       SELECT 1
                       FROM pg_class relation_info
                       JOIN pg_namespace namespace_info ON namespace_info.oid = relation_info.relnamespace
                       WHERE namespace_info.nspname <> 'information_schema'
                         AND namespace_info.nspname NOT LIKE 'pg_%'
                         AND relation_info.relkind IN ('r', 'p', 'v', 'm', 'f')
                         AND (has_table_privilege(current_user, relation_info.oid, 'INSERT')
                              OR has_table_privilege(current_user, relation_info.oid, 'UPDATE')
                              OR has_table_privilege(current_user, relation_info.oid, 'DELETE')
                              OR has_table_privilege(current_user, relation_info.oid, 'TRUNCATE')
                              OR has_table_privilege(current_user, relation_info.oid, 'TRIGGER')
                              OR has_table_privilege(current_user, relation_info.oid, 'REFERENCES')
                              OR has_table_privilege(current_user, relation_info.oid, 'MAINTAIN'))
                   ) AS table_write_privileges_denied,
                   NOT EXISTS (
                       SELECT 1
                       FROM pg_class sequence_info
                       JOIN pg_namespace namespace_info ON namespace_info.oid = sequence_info.relnamespace
                       WHERE sequence_info.relkind = 'S'
                         AND namespace_info.nspname <> 'information_schema'
                         AND namespace_info.nspname NOT LIKE 'pg_%'
                         AND (has_sequence_privilege(current_user, sequence_info.oid, 'USAGE')
                              OR has_sequence_privilege(current_user, sequence_info.oid, 'UPDATE'))
                   ) AS sequence_mutation_denied,
                   NOT EXISTS (
                       SELECT 1
                       FROM pg_class relation_info
                       JOIN pg_namespace namespace_info ON namespace_info.oid = relation_info.relnamespace
                       WHERE namespace_info.nspname <> 'information_schema'
                         AND namespace_info.nspname NOT LIKE 'pg_%'
                         AND relation_info.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                   ) AS no_owned_relations,
                   NOT EXISTS (
                       SELECT 1
                       FROM pg_namespace namespace_info
                       WHERE namespace_info.nspname <> 'information_schema'
                         AND namespace_info.nspname NOT LIKE 'pg_%'
                         AND namespace_info.nspowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                   ) AS no_owned_schemas
            """;

    private static final Set<String> REQUIRED_TRUE_COLUMNS = Set.of(
            "expected_database",
            "session_identity_matches",
            "current_role_can_login",
            "not_database_owner",
            "safe_role_attributes",
            "expected_read_only_role_membership",
            "no_unexpected_role_membership",
            "database_create_denied",
            "schema_create_denied",
            "table_write_privileges_denied",
            "sequence_mutation_denied",
            "no_owned_relations",
            "no_owned_schemas");

    private static final int MAX_COLUMNS = 100;
    private static final int MAX_CELL_CHARACTERS = 4_096;
    private static final int MAX_PLAN_CHARACTERS = 65_536;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ReadOnlySqlValidator validator;
    private final String expectedDatabase;
    private final String expectedReadOnlyRole;
    private final int maximumRows;
    private final int queryTimeoutSeconds;

    public ReadOnlyQueryExecutor(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ReadOnlySqlValidator validator,
            String expectedDatabase,
            String expectedReadOnlyRole,
            int maximumRows,
            int queryTimeoutSeconds) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.expectedDatabase = requireNonBlank(expectedDatabase, "expectedDatabase");
        this.expectedReadOnlyRole = requireRoleName(expectedReadOnlyRole);
        if (maximumRows < 1 || maximumRows > 2_000) {
            throw new IllegalArgumentException("maximumRows must be between 1 and 2000.");
        }
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 30) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be between 1 and 30.");
        }
        this.maximumRows = maximumRows;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setReadOnly(true);
        this.transaction.setTimeout(queryTimeoutSeconds);
    }

    public QueryResponse execute(String sql) {
        ReadOnlySqlValidator.ValidationResponse validation = validator.validate(sql);
        if (!validation.valid()) {
            throw new QueryRejectedException(validation.errors());
        }

        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                // This must be the first statement in the transaction. It is a
                // server-enforced boundary even if a validator misses a write.
                jdbc.execute("SET TRANSACTION READ ONLY");
                jdbc.execute("SET LOCAL statement_timeout = '" + (queryTimeoutSeconds * 1_000) + "ms'");
                assertReadOnlyRuntimeRole();
                return executeBoundedQuery(sql);
            }), "The read-only query transaction returned no result.");
        } catch (QueryRejectedException | QueryExecutionUnavailableException | QueryExecutionException known) {
            throw known;
        } catch (DataAccessException | TransactionException failure) {
            // Do not expose SQL, schema names, driver messages, or connection details.
            throw new QueryExecutionException();
        }
    }

    /**
     * Returns a PostgreSQL JSON execution plan without running the submitted query.
     * EXPLAIN ANALYZE is intentionally never used; the request still runs in a
     * read-only transaction after the same dedicated-role checks as normal SQL.
     */
    public ExplainResponse explain(String sql) {
        ReadOnlySqlValidator.ValidationResponse validation = validator.validate(sql);
        if (!validation.valid()) {
            throw new QueryRejectedException(validation.errors());
        }

        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                jdbc.execute("SET TRANSACTION READ ONLY");
                jdbc.execute("SET LOCAL statement_timeout = '" + (queryTimeoutSeconds * 1_000) + "ms'");
                assertReadOnlyRuntimeRole();

                long startedAt = System.nanoTime();
                String planJson = jdbc.queryForObject(
                        "EXPLAIN (FORMAT JSON, COSTS TRUE, VERBOSE FALSE, SETTINGS FALSE) " + sql,
                        (resultSet, rowNumber) -> resultSet.getString(1));
                if (planJson == null || planJson.length() > MAX_PLAN_CHARACTERS) {
                    throw new QueryExecutionException();
                }
                long elapsedMillis = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
                return new ExplainResponse(planJson, elapsedMillis, false);
            }), "The read-only EXPLAIN transaction returned no result.");
        } catch (QueryRejectedException | QueryExecutionUnavailableException | QueryExecutionException known) {
            throw known;
        } catch (DataAccessException | TransactionException failure) {
            // Do not expose SQL, schema names, driver messages, or connection details.
            throw new QueryExecutionException();
        }
    }

    private void assertReadOnlyRuntimeRole() {
        Map<String, Object> checks = jdbc.queryForMap(
                ROLE_READINESS_SQL,
                expectedDatabase,
                expectedReadOnlyRole,
                expectedReadOnlyRole);
        boolean ready = REQUIRED_TRUE_COLUMNS.stream()
                .allMatch(column -> Boolean.TRUE.equals(checks.get(column)));
        if (!ready) {
            throw new QueryExecutionUnavailableException();
        }
    }

    private QueryResponse executeBoundedQuery(String sql) {
        long startedAt = System.nanoTime();
        PreparedStatementCreator statementCreator = connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setMaxRows(maximumRows + 1);
            statement.setFetchSize(Math.min(maximumRows + 1, 100));
            statement.setQueryTimeout(queryTimeoutSeconds);
            return statement;
        };
        ResultSetExtractor<QueryResponse> resultExtractor = resultSet -> {
            ResultSetMetaData metadata = resultSet.getMetaData();
            int columnCount = metadata.getColumnCount();
            if (columnCount > MAX_COLUMNS) {
                throw new QueryExecutionException();
            }

            List<ColumnInfo> columns = new ArrayList<>(columnCount);
            for (int column = 1; column <= columnCount; column++) {
                columns.add(new ColumnInfo(
                        metadata.getColumnLabel(column),
                        metadata.getColumnTypeName(column),
                        metadata.getColumnType(column)));
            }

            List<List<String>> rows = new ArrayList<>(Math.min(maximumRows, 100));
            boolean truncated = false;
            while (resultSet.next()) {
                if (rows.size() == maximumRows) {
                    truncated = true;
                    break;
                }
                List<String> row = new ArrayList<>(columnCount);
                for (int column = 1; column <= columnCount; column++) {
                    row.add(safeCellValue(resultSet.getObject(column)));
                }
                rows.add(row);
            }

            long elapsedMillis = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
            return new QueryResponse(
                    List.copyOf(columns),
                    List.copyOf(rows),
                    rows.size(),
                    truncated,
                    elapsedMillis);
        };
        return jdbc.query(statementCreator, resultExtractor);
    }

    private static String safeCellValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] || value instanceof Blob || value instanceof Clob) {
            return "[binary or large-object value omitted]";
        }
        String text = value.toString();
        if (text.length() <= MAX_CELL_CHARACTERS) {
            return text;
        }
        return text.substring(0, MAX_CELL_CHARACTERS) + "… [truncated]";
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }

    private static String requireRoleName(String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("expectedReadOnlyRole must be a PostgreSQL role name.");
        }
        return value;
    }

    public record ColumnInfo(String name, String databaseType, int jdbcType) {}

    public record QueryResponse(
            List<ColumnInfo> columns,
            List<List<String>> rows,
            int rowCount,
            boolean truncated,
            long executionTimeMillis) {}

    public record ExplainResponse(String planJson, long elapsedTimeMillis, boolean analyze) {}

    public static final class QueryRejectedException extends RuntimeException {
        private final List<String> errors;

        public QueryRejectedException(List<String> errors) {
            super("The SQL draft did not pass read-only validation.");
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    public static final class QueryExecutionUnavailableException extends RuntimeException {
        public QueryExecutionUnavailableException() {
            super("A dedicated read-only database identity is not ready.");
        }
    }

    public static final class QueryExecutionException extends RuntimeException {
        public QueryExecutionException() {
            super("The query could not be completed.");
        }
    }
}
