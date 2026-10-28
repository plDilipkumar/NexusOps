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
    static final String LIST_ACTIVE_WORKSPACES_SQL = """
            SELECT w.workspace_id, w.name, w.slug, m.role
            FROM neurosql_app.workspace_memberships m
            JOIN neurosql_app.workspaces w ON w.workspace_id = m.workspace_id
            JOIN neurosql_app.app_users u ON u.user_id = m.user_id
            WHERE m.user_id = ?
              AND m.status = 'ACTIVE'
              AND u.status = 'ACTIVE'
            ORDER BY lower(w.name), w.workspace_id
            """;

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
     * Lists only active workspaces where the supplied account has an active
     * membership. Unknown stored roles are omitted rather than exposed.
     */
    public List<ActiveWorkspace> listActiveWorkspaces(UUID userId) {
        if (userId == null) {
            return List.of();
        }

        List<Optional<ActiveWorkspace>> rows = jdbcTemplate.query(
                LIST_ACTIVE_WORKSPACES_SQL,
                (resultSet, rowNumber) -> {
                    Optional<WorkspaceRole> role = WorkspaceRole.fromDatabase(resultSet.getString("role"));
                    if (role.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ActiveWorkspace(
                            resultSet.getObject("workspace_id", UUID.class),
                            resultSet.getString("name"),
                            resultSet.getString("slug"),
                            role.get()));
                },
                userId);
        return rows.stream().flatMap(Optional::stream).toList();
    }

    public record ActiveWorkspace(UUID workspaceId, String name, String slug, WorkspaceRole role) {
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
