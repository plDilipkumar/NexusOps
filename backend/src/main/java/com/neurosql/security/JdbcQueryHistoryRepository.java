package com.neurosql.security;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
                + "'${neurosql.sql-history.enabled:false}' == 'true'")
public class JdbcQueryHistoryRepository {
    static final String INSERT_SQL = """
            INSERT INTO neurosql_app.query_history
                (workspace_id, user_id, query_text, query_hash, outcome,
                 row_count, execution_time_ms, truncated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final Set<String> OUTCOMES = Set.of("SUCCEEDED", "FAILED", "CANCELED");

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

    private static final String LIST_FIRST_PAGE_SQL = """
            SELECT history_id, query_text, outcome, row_count,
                   execution_time_ms, truncated, created_at
            FROM neurosql_app.query_history
            WHERE workspace_id = ?
              AND user_id = ?
              AND (?::varchar IS NULL OR outcome = ?::varchar)
              AND (?::date IS NULL OR created_at >= (?::date::timestamp AT TIME ZONE 'UTC'))
              AND (?::date IS NULL OR created_at < ((?::date + 1)::timestamp AT TIME ZONE 'UTC'))
              AND (?::varchar IS NULL OR STRPOS(LOWER(query_text), LOWER(?::varchar)) > 0)
            ORDER BY created_at DESC, history_id DESC
            LIMIT ?
            """;

    private static final String LIST_AFTER_CURSOR_SQL = """
            SELECT history_id, query_text, outcome, row_count,
                   execution_time_ms, truncated, created_at
            FROM neurosql_app.query_history
            WHERE workspace_id = ?
              AND user_id = ?
              AND (created_at, history_id) < (?::timestamptz, ?::uuid)
              AND (?::varchar IS NULL OR outcome = ?::varchar)
              AND (?::date IS NULL OR created_at >= (?::date::timestamp AT TIME ZONE 'UTC'))
              AND (?::date IS NULL OR created_at < ((?::date + 1)::timestamp AT TIME ZONE 'UTC'))
              AND (?::varchar IS NULL OR STRPOS(LOWER(query_text), LOWER(?::varchar)) > 0)
            ORDER BY created_at DESC, history_id DESC
            LIMIT ?
            """;

    /**
     * Returns only one user's rows in one already-authorized workspace.
     * The caller supplies pageSize + 1 to detect whether another page exists.
     */
    public List<HistoryEntry> findRecent(
            UUID workspaceId, UUID userId, int limit, QueryHistoryCursor.Position before, String outcome) {
        return findRecent(workspaceId, userId, limit, before, outcome, null, null, null);
    }

    public List<HistoryEntry> findRecent(
            UUID workspaceId, UUID userId, int limit, QueryHistoryCursor.Position before,
            String outcome, LocalDate fromDate, LocalDate toDate) {
        return findRecent(workspaceId, userId, limit, before, outcome, fromDate, toDate, null);
    }

    public List<HistoryEntry> findRecent(
            UUID workspaceId, UUID userId, int limit, QueryHistoryCursor.Position before,
            String outcome, LocalDate fromDate, LocalDate toDate, String querySearch) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(userId, "userId");
        if (limit < 1 || limit > QueryHistoryReadService.MAX_PAGE_SIZE + 1) {
            throw new IllegalArgumentException("History read limit is outside the supported range.");
        }
        if (outcome != null && !OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("History outcome filter is invalid.");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("History date range is invalid.");
        }
        java.sql.Date fromParameter = fromDate == null ? null : java.sql.Date.valueOf(fromDate);
        java.sql.Date toParameter = toDate == null ? null : java.sql.Date.valueOf(toDate);

        if (before == null) {
            return jdbcTemplate.query(
                    LIST_FIRST_PAGE_SQL,
                    (resultSet, rowNumber) -> mapHistoryEntry(resultSet),
                    workspaceId,
                    userId,
                    outcome,
                    outcome,
                    fromParameter,
                    fromParameter,
                    toParameter,
                    toParameter,
                    querySearch,
                    querySearch,
                    limit);
        }

        return jdbcTemplate.query(
                LIST_AFTER_CURSOR_SQL,
                (resultSet, rowNumber) -> mapHistoryEntry(resultSet),
                workspaceId,
                userId,
                before.createdAt().toString(),
                before.historyId().toString(),
                outcome,
                outcome,
                fromParameter,
                fromParameter,
                toParameter,
                toParameter,
                querySearch,
                querySearch,
                limit);
    }

    private static HistoryEntry mapHistoryEntry(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new HistoryEntry(
                resultSet.getObject("history_id", UUID.class),
                resultSet.getString("query_text"),
                resultSet.getString("outcome"),
                resultSet.getObject("row_count", Integer.class),
                resultSet.getInt("execution_time_ms"),
                resultSet.getBoolean("truncated"),
                Objects.requireNonNull(resultSet.getTimestamp("created_at"), "created_at").toInstant());
    }

    public record HistoryEntry(
            UUID historyId,
            String queryText,
            String outcome,
            Integer rowCount,
            int executionTimeMillis,
            boolean truncated,
            java.time.Instant createdAt) {
    }
}
