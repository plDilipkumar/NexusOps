package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceAuthorizationServiceTest {
    private static final UUID USER_ID = UUID.fromString("b4f41d45-68dc-4f7b-8df0-48e3f29e54c1");
    private static final UUID WORKSPACE_ID = UUID.fromString("4cfa688e-8890-4b5e-8a9a-19f074d5a1f3");

    @Mock
    private JdbcWorkspaceAuthorizationRepository memberships;

    private WorkspaceAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceAuthorizationService(memberships);
    }

    @Test
    void roleHierarchyAllowsOnlyAtOrAboveMinimum() {
        when(memberships.findActiveRole(USER_ID, WORKSPACE_ID)).thenReturn(Optional.of(WorkspaceRole.ADMIN));

        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER)).isTrue();
        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.MEMBER)).isTrue();
        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.ADMIN)).isTrue();
        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.OWNER)).isFalse();

        verify(memberships, org.mockito.Mockito.times(4)).findActiveRole(USER_ID, WORKSPACE_ID);
    }

    @Test
    void deniesMissingMembershipAndNullArguments() {
        when(memberships.findActiveRole(USER_ID, WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER)).isFalse();
        assertThat(service.hasMinimumRole(null, WORKSPACE_ID, WorkspaceRole.VIEWER)).isFalse();
        assertThat(service.hasMinimumRole(USER_ID, null, WorkspaceRole.VIEWER)).isFalse();
        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, null)).isFalse();

        verify(memberships).findActiveRole(USER_ID, WORKSPACE_ID);
    }

    @Test
    void unknownDatabaseRolesCannotGrantAccess() {
        assertThat(WorkspaceRole.fromDatabase("SUPERUSER")).isEmpty();
        assertThat(WorkspaceRole.fromDatabase(null)).isEmpty();
        assertThat(WorkspaceRole.fromDatabase("OWNER")).contains(WorkspaceRole.OWNER);
    }

    @Test
    void viewerCannotMeetMemberMinimumAndOwnerMeetsOwnerMinimum() {
        when(memberships.findActiveRole(USER_ID, WORKSPACE_ID)).thenReturn(Optional.of(WorkspaceRole.VIEWER));

        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER)).isTrue();
        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.MEMBER)).isFalse();

        when(memberships.findActiveRole(USER_ID, WORKSPACE_ID)).thenReturn(Optional.of(WorkspaceRole.OWNER));
        assertThat(service.hasMinimumRole(USER_ID, WORKSPACE_ID, WorkspaceRole.OWNER)).isTrue();

        verify(memberships, org.mockito.Mockito.times(3)).findActiveRole(USER_ID, WORKSPACE_ID);
    }
}
