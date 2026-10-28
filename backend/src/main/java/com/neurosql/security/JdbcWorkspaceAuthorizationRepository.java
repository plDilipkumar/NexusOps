package com.neurosql.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only workspace membership lookup for the proposed durable-auth schema.
 *
 * <p>This bean remains disabled until the durable schema and least-privileged
 * database grants are approved. It is not wired to any HTTP endpoint.</p>
 */
@Repository
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class JdbcWorkspaceAuthorizationRepository {
    static final String FIND_ACTIVE_ROLE_SQL = """
            SELECT m.role
            FROM neurosql_app.workspace_memberships m
            JOIN neurosql_app.workspaces w ON w.workspace_id = m.workspace_id
            JOIN neurosql_app.app_users u ON u.user_id = m.user_id
            WHERE m.workspace_id = ?
              AND m.user_id = ?
              AND m.status = 'ACTIVE'
              AND u.status = 'ACTIVE'
            LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkspaceAuthorizationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the role only for an existing workspace, an active membership,
     * and an active account. Invalid IDs and unknown role values fail closed.
     */
    public Optional<WorkspaceRole> findActiveRole(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return Optional.empty();
        }

        List<String> roles = jdbcTemplate.query(
                FIND_ACTIVE_ROLE_SQL,
                (resultSet, rowNumber) -> resultSet.getString("role"),
                workspaceId,
                userId);
        return roles.stream().findFirst().flatMap(WorkspaceRole::fromDatabase);
    }
}
