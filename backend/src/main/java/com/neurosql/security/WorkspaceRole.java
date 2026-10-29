package com.neurosql.security;

import java.util.Optional;

/** Roles supported by the proposed workspace-membership schema. */
public enum WorkspaceRole {
    VIEWER(1),
    MEMBER(2),
    ADMIN(3),
    OWNER(4);

    private final int level;

    WorkspaceRole(int level) {
        this.level = level;
    }

    public boolean isAtLeast(WorkspaceRole minimumRole) {
        return minimumRole != null && level >= minimumRole.level;
    }

    /** Unknown database values fail closed rather than granting a role. */
    public static Optional<WorkspaceRole> fromDatabase(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value));
        } catch (IllegalArgumentException invalidRole) {
            return Optional.empty();
        }
    }
}
