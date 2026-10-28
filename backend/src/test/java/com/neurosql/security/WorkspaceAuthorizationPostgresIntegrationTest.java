package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises workspace authorization SQL against the disposable PostgreSQL 17
 * service used by CI. It creates only a minimal test fixture; it is not a
 * production migration and must never point to a shared or production DB.
 */
@EnabledIfEnvironmentVariable(named = "NEUROSQL_TEST_JDBC_URL", matches = "^jdbc:postgresql:.*$")
class WorkspaceAuthorizationPostgresIntegrationTest {
    private JdbcTemplate jdbc;
    private WorkspaceAuthorizationService authorization;

    @BeforeEach
    void setUpIsolatedPostgresFixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv("NEUROSQL_TEST_JDBC_URL"));
        dataSource.setUsername(System.getenv("NEUROSQL_TEST_DB_USERNAME"));
        dataSource.setPassword(System.getenv("NEUROSQL_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);

        // This fixture is compatible with the relevant columns in the proposed schema.
        // IF NOT EXISTS allows this class to share the disposable CI database with
        // the session repository integration tests without dropping any objects.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS neurosql_app");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.app_users (
                    user_id UUID PRIMARY KEY,
                    status VARCHAR(24) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.workspaces (
                    workspace_id UUID PRIMARY KEY,
                    name VARCHAR(120) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.workspace_memberships (
                    workspace_id UUID NOT NULL REFERENCES neurosql_app.workspaces(workspace_id),
                    user_id UUID NOT NULL REFERENCES neurosql_app.app_users(user_id),
                    role VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    PRIMARY KEY (workspace_id, user_id)
                )
                """);

        authorization = new WorkspaceAuthorizationService(new JdbcWorkspaceAuthorizationRepository(jdbc));
    }

    @Test
    void activeMembershipEnforcesMinimumRoleAndWorkspaceIsolation() {
        UUID activeUser = insertUser("ACTIVE");
        UUID workspaceId = insertWorkspace("team-a");
        UUID otherWorkspaceId = insertWorkspace("team-b");
        insertMembership(workspaceId, activeUser, "ADMIN", "ACTIVE");

        assertThat(authorization.hasMinimumRole(activeUser, workspaceId, WorkspaceRole.VIEWER)).isTrue();
        assertThat(authorization.hasMinimumRole(activeUser, workspaceId, WorkspaceRole.MEMBER)).isTrue();
        assertThat(authorization.hasMinimumRole(activeUser, workspaceId, WorkspaceRole.ADMIN)).isTrue();
        assertThat(authorization.hasMinimumRole(activeUser, workspaceId, WorkspaceRole.OWNER)).isFalse();
        assertThat(authorization.hasMinimumRole(activeUser, otherWorkspaceId, WorkspaceRole.VIEWER)).isFalse();
    }

    @Test
    void inactiveAccountsAndSuspendedMembershipsAreDenied() {
        UUID disabledUser = insertUser("DISABLED");
        UUID suspendedUser = insertUser("ACTIVE");
        UUID workspaceId = insertWorkspace("team-c");
        insertMembership(workspaceId, disabledUser, "OWNER", "ACTIVE");
        insertMembership(workspaceId, suspendedUser, "VIEWER", "SUSPENDED");

        assertThat(authorization.hasMinimumRole(disabledUser, workspaceId, WorkspaceRole.VIEWER)).isFalse();
        assertThat(authorization.hasMinimumRole(suspendedUser, workspaceId, WorkspaceRole.VIEWER)).isFalse();
        assertThat(authorization.hasMinimumRole(null, workspaceId, WorkspaceRole.VIEWER)).isFalse();
        assertThat(authorization.hasMinimumRole(suspendedUser, workspaceId, null)).isFalse();
    }

    private UUID insertUser(String status) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO neurosql_app.app_users (user_id, status) VALUES (?, ?)", userId, status);
        return userId;
    }

    private UUID insertWorkspace(String name) {
        UUID workspaceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO neurosql_app.workspaces (workspace_id, name) VALUES (?, ?)",
                workspaceId,
                name);
        return workspaceId;
    }

    private void insertMembership(UUID workspaceId, UUID userId, String role, String status) {
        jdbc.update("""
                INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role, status)
                VALUES (?, ?, ?, ?)
                """,
                workspaceId,
                userId,
                role,
                status);
    }
}
