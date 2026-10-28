package com.neurosql.security;

import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Applies the explicit workspace role hierarchy to active membership lookups.
 *
 * <p>Owner, admin, member, and viewer are ordered from highest to lowest.
 * The service is feature-gated and not connected to HTTP routes until the
 * schema and runtime database privileges have been approved.</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public class WorkspaceAuthorizationService {
    private final JdbcWorkspaceAuthorizationRepository memberships;

    public WorkspaceAuthorizationService(JdbcWorkspaceAuthorizationRepository memberships) {
        this.memberships = Objects.requireNonNull(memberships, "memberships");
    }

    /**
     * Checks whether an active account's active membership meets the required
     * minimum role. Missing IDs, missing membership, inactive accounts, and
     * unknown stored role values all result in denial.
     */
    public boolean hasMinimumRole(UUID userId, UUID workspaceId, WorkspaceRole minimumRole) {
        if (userId == null || workspaceId == null || minimumRole == null) {
            return false;
        }
        return memberships.findActiveRole(userId, workspaceId)
                .map(actualRole -> actualRole.isAtLeast(minimumRole))
                .orElse(false);
    }
}
