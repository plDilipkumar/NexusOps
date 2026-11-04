package com.neurosql.security;

import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first personal workspace for a newly registered account.
 *
 * <p>The caller must include account creation, workspace creation, owner
 * membership, and verification-token issuance in one transaction. The
 * deferred database invariant requires every workspace to have an active
 * owner when that transaction commits.</p>
 */
@Repository
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class JdbcWorkspaceProvisioningRepository {
    static final String CREATE_PERSONAL_WORKSPACE_SQL = """
            INSERT INTO neurosql_app.workspaces (name, slug, created_by_user_id)
            VALUES (?, ?, ?)
            RETURNING workspace_id
            """;

    static final String ADD_OWNER_MEMBERSHIP_SQL = """
            INSERT INTO neurosql_app.workspace_memberships
                (workspace_id, user_id, role, status)
            VALUES (?, ?, 'OWNER', 'ACTIVE')
            """;

    private static final String WORKSPACE_SUFFIX = " Workspace";

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkspaceProvisioningRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * Creates a unique personal workspace and its sole active owner membership.
     * This method must run inside the account-registration transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createPersonalWorkspace(UUID userId, String displayName) {
        Objects.requireNonNull(userId, "userId");
        String normalizedDisplayName = JdbcAccountRepository.normalizeDisplayName(displayName);
        String workspaceName = workspaceName(normalizedDisplayName);
        String slug = "personal-" + userId.toString().replace("-", "");

        UUID workspaceId = jdbcTemplate.queryForObject(
                CREATE_PERSONAL_WORKSPACE_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("workspace_id", UUID.class),
                workspaceName,
                slug,
                userId);
        if (workspaceId == null) {
            throw new IllegalStateException("The database did not return the personal workspace identifier.");
        }

        int memberships = jdbcTemplate.update(ADD_OWNER_MEMBERSHIP_SQL, workspaceId, userId);
        if (memberships != 1) {
            throw new IllegalStateException("The personal workspace owner membership was not created.");
        }
        return workspaceId;
    }

    static String workspaceName(String displayName) {
        int maxPrefixCodePoints = 120 - WORKSPACE_SUFFIX.length();
        int codePointCount = displayName.codePointCount(0, displayName.length());
        int prefixCodePoints = Math.min(codePointCount, maxPrefixCodePoints);
        int prefixEnd = displayName.offsetByCodePoints(0, prefixCodePoints);
        return displayName.substring(0, prefixEnd) + WORKSPACE_SUFFIX;
    }
}
