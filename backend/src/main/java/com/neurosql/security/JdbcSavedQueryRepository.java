package com.neurosql.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter for the proposed V5 saved_queries table.
 *
 * <p>It uses the control-plane datasource and remains disabled until durable
 * authentication, shared rate limiting, the V5 schema, and runtime grants are
 * deliberately enabled.</p>
 */
@Repository
@ConditionalOnExpression(
        "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true' and "
                + "'${neurosql.saved-queries.enabled:false}' == 'true'")
public class JdbcSavedQueryRepository {
    static final String LIST_FIRST_PAGE_SQL = """
            SELECT saved_query_id, workspace_id, owner_user_id, name, description,
                   query_text, created_at, updated_at
            FROM neurosql_app.saved_queries
            WHERE workspace_id = ?
            ORDER BY updated_at DESC, saved_query_id DESC
            LIMIT ?
            """;

    static final String LIST_AFTER_CURSOR_SQL = """
            SELECT saved_query_id, workspace_id, owner_user_id, name, description,
                   query_text, created_at, updated_at
            FROM neurosql_app.saved_queries
            WHERE workspace_id = ?
              AND (updated_at, saved_query_id) < (?::timestamptz, ?::uuid)
            ORDER BY updated_at DESC, saved_query_id DESC
            LIMIT ?
            """;

    static final String INSERT_SQL = """
            INSERT INTO neurosql_app.saved_queries
                (workspace_id, owner_user_id, name, description, query_text)
            VALUES (?, ?, ?, ?, ?)
            RETURNING saved_query_id, workspace_id, owner_user_id, name, description,
                      query_text, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcSavedQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * Lists saved queries shared with members of one already-authorized workspace.
     * The caller supplies pageSize + 1 to determine whether another page exists.
     */
    public List<SavedQueryEntry> findRecent(
            UUID workspaceId,
            int limit,
            SavedQueryCursor.Position before) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (limit < 1 || limit > SavedQueryService.MAX_PAGE_SIZE + 1) {
            throw new IllegalArgumentException("Saved-query read limit is outside the supported range.");
        }

        if (before == null) {
            return jdbcTemplate.query(
                    LIST_FIRST_PAGE_SQL,
                    JdbcSavedQueryRepository::mapSavedQuery,
                    workspaceId,
                    limit);
        }

        return jdbcTemplate.query(
                LIST_AFTER_CURSOR_SQL,
                JdbcSavedQueryRepository::mapSavedQuery,
                workspaceId,
                Timestamp.from(before.updatedAt()),
                before.savedQueryId(),
                limit);
    }

    public SavedQueryEntry insert(
            UUID workspaceId,
            UUID ownerUserId,
            String name,
            String description,
            String queryText) {
        return jdbcTemplate.queryForObject(
                INSERT_SQL,
                JdbcSavedQueryRepository::mapSavedQuery,
                workspaceId,
                ownerUserId,
                name,
                description,
                queryText);
    }

    private static SavedQueryEntry mapSavedQuery(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SavedQueryEntry(
                resultSet.getObject("saved_query_id", UUID.class),
                resultSet.getObject("workspace_id", UUID.class),
                resultSet.getObject("owner_user_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("description"),
                resultSet.getString("query_text"),
                Objects.requireNonNull(resultSet.getTimestamp("created_at"), "created_at").toInstant(),
                Objects.requireNonNull(resultSet.getTimestamp("updated_at"), "updated_at").toInstant());
    }

    public record SavedQueryEntry(
            UUID savedQueryId,
            UUID workspaceId,
            UUID ownerUserId,
            String name,
            String description,
            String queryText,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
    }
}
