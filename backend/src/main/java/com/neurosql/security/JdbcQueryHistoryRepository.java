package com.neurosql.security;

import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append-only adapter for the proposed V5 query-history table.
 *
 * <p>This uses the control-plane datasource, never the dedicated read-only SQL
 * datasource. It is feature-gated until the V1-V5 schema and runtime grants
 * have been approved and provisioned.</p>
 */
@Repository
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.sql-history.enabled:false}' == 'true')
public class JdbcQueryHistoryRepository {
    static final String INSERT_SQL = """
            INSERT INTO neurosql_app.query_history
                (workspace_id, user_id, query_text, query_hash, outcome,
                 row_count, execution_time_ms, truncated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcQueryHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    public void append(
            UUID workspaceId,
            UUID userId,
            String queryText,
            String queryHash,
            String outcome,
            Integer rowCount,
            int executionTimeMillis,
            boolean truncated) {
        jdbcTemplate.update(
                INSERT_SQL,
                workspaceId,
                userId,
                queryText,
                queryHash,
                outcome,
                rowCount,
                executionTimeMillis,
                truncated);
    }
}
