package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;

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
@EnabledIfEnvironmentVariable(named = "NEUROSQL_TEST_JDBC_URL", matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
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
                    name VARCHAR(120) NOT NULL,
                    slug VARCHAR(120) NOT NULL
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
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.query_history (
                    history_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    workspace_id UUID NOT NULL,
                    user_id UUID NOT NULL,
                    query_text TEXT NOT NULL,
                    query_hash VARCHAR(64) NOT NULL,
                    outcome VARCHAR(16) NOT NULL,
                    row_count INTEGER,
                    execution_time_ms INTEGER NOT NULL,
                    truncated BOOLEAN NOT NULL DEFAULT false,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    FOREIGN KEY (workspace_id, user_id)
                        REFERENCES neurosql_app.workspace_memberships (workspace_id, user_id)
                        ON DELETE CASCADE
                )
                """);


        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS neurosql_app.saved_queries (
                    saved_query_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    workspace_id UUID NOT NULL,
                    owner_user_id UUID NOT NULL,
                    name VARCHAR(120) NOT NULL CHECK (name <> '' AND name = btrim(name)),
                    description VARCHAR(500) NOT NULL DEFAULT '',
                    query_text TEXT NOT NULL CHECK (length(query_text) BETWEEN 1 AND 50000 AND btrim(query_text) <> ''),
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    FOREIGN KEY (workspace_id, owner_user_id)
                        REFERENCES neurosql_app.workspace_memberships (workspace_id, user_id)
                        ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_saved_queries_workspace_name
                    ON neurosql_app.saved_queries (workspace_id, lower(name))
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS ix_saved_queries_workspace_updated
                    ON neurosql_app.saved_queries (workspace_id, updated_at DESC, saved_query_id DESC)
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

    @Test
    void listsOnlyActiveWorkspaceMembershipsForActiveAccounts() {
        UUID activeUser = insertUser("ACTIVE");
        UUID inactiveUser = insertUser("DISABLED");
        UUID activeWorkspace = insertWorkspace("team-a");
        UUID suspendedWorkspace = insertWorkspace("team-b");
        insertMembership(activeWorkspace, activeUser, "ADMIN", "ACTIVE");
        insertMembership(suspendedWorkspace, activeUser, "VIEWER", "SUSPENDED");
        insertMembership(activeWorkspace, inactiveUser, "OWNER", "ACTIVE");

        var visible = authorization.listActiveWorkspaces(activeUser);
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).workspaceId()).isEqualTo(activeWorkspace);
        assertThat(visible.get(0).name()).isEqualTo("team-a");
        assertThat(visible.get(0).slug()).startsWith("team-a-");
        assertThat(visible.get(0).role()).isEqualTo(WorkspaceRole.ADMIN);
        assertThat(authorization.listActiveWorkspaces(inactiveUser)).isEmpty();
        assertThat(authorization.listActiveWorkspaces(null)).isEmpty();
    }

    @Test
    void appendsSuccessfulAndFailedHistoryAgainstTheExactWorkspaceMembership() {
        UUID firstUser = insertUser("ACTIVE");
        UUID secondUser = insertUser("ACTIVE");
        UUID firstWorkspace = insertWorkspace("history-a");
        UUID secondWorkspace = insertWorkspace("history-b");
        insertMembership(firstWorkspace, firstUser, "OWNER", "ACTIVE");
        insertMembership(firstWorkspace, secondUser, "VIEWER", "ACTIVE");
        insertMembership(secondWorkspace, firstUser, "ADMIN", "ACTIVE");

        QueryHistoryService history = new QueryHistoryService(new JdbcQueryHistoryRepository(jdbc));
        history.recordSucceeded(firstUser, firstWorkspace, " SELECT 1 ", 1, 7, false);
        history.recordFailed(firstUser, firstWorkspace, "SELECT broken FROM", 19);
        history.recordSucceeded(secondUser, firstWorkspace, "SELECT 2", 1, 3, true);
        history.recordSucceeded(firstUser, secondWorkspace, "SELECT 3", 1, 2, false);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.query_history WHERE workspace_id = ? AND user_id = ?",
                Integer.class, firstWorkspace, firstUser)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.query_history WHERE workspace_id = ? AND user_id = ?",
                Integer.class, firstWorkspace, secondUser)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM neurosql_app.query_history WHERE workspace_id = ? AND user_id = ?",
                Integer.class, secondWorkspace, firstUser)).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT query_hash FROM neurosql_app.query_history WHERE query_text = 'SELECT 1' AND workspace_id = ? AND user_id = ?",
                String.class, firstWorkspace, firstUser)).isEqualTo(QueryHistoryService.sha256("SELECT 1"));
        assertThat(jdbc.queryForObject(
                "SELECT row_count FROM neurosql_app.query_history WHERE outcome = 'FAILED'",
                Integer.class)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT truncated FROM neurosql_app.query_history WHERE outcome = 'FAILED'",
                Boolean.class)).isFalse();
    }

    @Test
    void readsHistoryWithKeysetPaginationAndStrictWorkspaceUserScope() {
        UUID firstUser = insertUser("ACTIVE");
        UUID secondUser = insertUser("ACTIVE");
        UUID firstWorkspace = insertWorkspace("history-page-a");
        UUID secondWorkspace = insertWorkspace("history-page-b");
        insertMembership(firstWorkspace, firstUser, "MEMBER", "ACTIVE");
        insertMembership(firstWorkspace, secondUser, "MEMBER", "ACTIVE");
        insertMembership(secondWorkspace, firstUser, "MEMBER", "ACTIVE");

        insertHistory(firstWorkspace, firstUser, "SELECT 1", "2026-11-02T10:01:00.000001Z");
        insertHistory(firstWorkspace, firstUser, "SELECT 2", "2026-11-02T10:02:00.000002Z");
        insertHistory(firstWorkspace, firstUser, "SELECT 3", "2026-11-02T10:03:00.000003Z");
        insertHistory(firstWorkspace, secondUser, "SELECT other user", "2026-11-02T10:04:00.000004Z");
        insertHistory(secondWorkspace, firstUser, "SELECT other workspace", "2026-11-02T10:05:00.000005Z");

        QueryHistoryReadService reader =
                new QueryHistoryReadService(new JdbcQueryHistoryRepository(jdbc));
        QueryHistoryReadService.QueryHistoryPage firstPage =
                reader.list(firstUser, firstWorkspace, 2, null);

        assertThat(firstPage.items())
                .extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT 3", "SELECT 2");
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();

        QueryHistoryReadService.QueryHistoryPage secondPage =
                reader.list(firstUser, firstWorkspace, 2, firstPage.nextCursor());
        assertThat(secondPage.items())
                .extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT 1");
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();

        assertThat(reader.list(secondUser, firstWorkspace, 10, null).items())
                .extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT other user");
        assertThat(reader.list(firstUser, secondWorkspace, 10, null).items())
                .extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT other workspace");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> reader.list(secondUser, firstWorkspace, 10, firstPage.nextCursor())))
                .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void savedQueriesAreSharedOnlyWithinTheirWorkspaceAndUseStableKeysetPagination() {
        UUID firstUser = insertUser("ACTIVE");
        UUID secondUser = insertUser("ACTIVE");
        UUID firstWorkspace = insertWorkspace("saved-query-a");
        UUID secondWorkspace = insertWorkspace("saved-query-b");
        insertMembership(firstWorkspace, firstUser, "OWNER", "ACTIVE");
        insertMembership(firstWorkspace, secondUser, "MEMBER", "ACTIVE");
        insertMembership(secondWorkspace, firstUser, "ADMIN", "ACTIVE");

        SavedQueryService savedQueries = new SavedQueryService(new JdbcSavedQueryRepository(jdbc));
        var first = savedQueries.create(firstWorkspace, firstUser, "Monthly revenue", "Finance", "SELECT 1");
        var second = savedQueries.create(firstWorkspace, secondUser, "Open invoices", "", "SELECT 2");
        var otherWorkspace = savedQueries.create(secondWorkspace, firstUser, "Private report", "", "SELECT 3");

        SavedQueryService.SavedQueryPage firstPage = savedQueries.list(firstWorkspace, 1, null);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();

        SavedQueryService.SavedQueryPage secondPage =
                savedQueries.list(firstWorkspace, 1, firstPage.nextCursor());
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();

        assertThat(java.util.stream.Stream.concat(firstPage.items().stream(), secondPage.items().stream())
                .map(JdbcSavedQueryRepository.SavedQueryEntry::savedQueryId).toList())
                .containsExactlyInAnyOrder(first.savedQueryId(), second.savedQueryId());
        assertThat(savedQueries.list(secondWorkspace, 10, null).items())
                .extracting(JdbcSavedQueryRepository.SavedQueryEntry::savedQueryId)
                .containsExactly(otherWorkspace.savedQueryId());
    }

    @Test
    void savedQueryNamesAreUniqueWithoutCrossWorkspaceCollisions() {
        UUID user = insertUser("ACTIVE");
        UUID firstWorkspace = insertWorkspace("saved-name-a");
        UUID secondWorkspace = insertWorkspace("saved-name-b");
        insertMembership(firstWorkspace, user, "OWNER", "ACTIVE");
        insertMembership(secondWorkspace, user, "OWNER", "ACTIVE");

        SavedQueryService savedQueries = new SavedQueryService(new JdbcSavedQueryRepository(jdbc));
        savedQueries.create(firstWorkspace, user, "Monthly", "", "SELECT 1");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> savedQueries.create(firstWorkspace, user, "monthly", "", "SELECT 2")))
                .isInstanceOf(SavedQueryService.DuplicateNameException.class);

        assertThat(savedQueries.create(secondWorkspace, user, "monthly", "", "SELECT 2"))
                .isNotNull();
    }

    @Test
    void savedQueriesCanBeUpdatedAndDeletedOnlyWithinTheirWorkspace() {
        UUID owner = insertUser("ACTIVE");
        UUID workspace = insertWorkspace("saved-query-mutation-a");
        UUID otherWorkspace = insertWorkspace("saved-query-mutation-b");
        insertMembership(workspace, owner, "OWNER", "ACTIVE");
        insertMembership(otherWorkspace, owner, "ADMIN", "ACTIVE");

        SavedQueryService savedQueries = new SavedQueryService(new JdbcSavedQueryRepository(jdbc));
        var created = savedQueries.create(workspace, owner, "Original", "Before", "SELECT 1");

        assertThat(savedQueries.update(
                otherWorkspace, created.savedQueryId(), "Cross workspace", "", "SELECT 9"))
                .isEmpty();
        assertThat(savedQueries.delete(otherWorkspace, created.savedQueryId())).isFalse();
        assertThat(savedQueries.list(workspace, 25, null).items())
                .extracting(JdbcSavedQueryRepository.SavedQueryEntry::savedQueryId)
                .containsExactly(created.savedQueryId());

        var updated = savedQueries.update(
                workspace, created.savedQueryId(), "Updated", "After", "SELECT 2").orElseThrow();
        assertThat(updated.ownerUserId()).isEqualTo(owner);
        assertThat(updated.workspaceId()).isEqualTo(workspace);
        assertThat(updated.name()).isEqualTo("Updated");
        assertThat(updated.description()).isEqualTo("After");
        assertThat(updated.queryText()).isEqualTo("SELECT 2");
        assertThat(updated.updatedAt()).isAfter(created.updatedAt());

        assertThat(savedQueries.delete(workspace, created.savedQueryId())).isTrue();
        assertThat(savedQueries.list(workspace, 25, null).items()).isEmpty();
    }

    @Test
    void updatingSavedQueryToAnotherCaseInsensitiveWorkspaceNameIsRejected() {
        UUID owner = insertUser("ACTIVE");
        UUID workspace = insertWorkspace("saved-query-update-duplicate");
        insertMembership(workspace, owner, "OWNER", "ACTIVE");

        SavedQueryService savedQueries = new SavedQueryService(new JdbcSavedQueryRepository(jdbc));
        savedQueries.create(workspace, owner, "Monthly", "", "SELECT 1");
        var second = savedQueries.create(workspace, owner, "Quarterly", "", "SELECT 2");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> savedQueries.update(workspace, second.savedQueryId(), "monthly", "", "SELECT 3")))
                .isInstanceOf(SavedQueryService.DuplicateNameException.class);
        assertThat(savedQueries.list(workspace, 25, null).items())
                .extracting(JdbcSavedQueryRepository.SavedQueryEntry::name)
                .containsExactlyInAnyOrder("Monthly", "Quarterly");
    }

    @Test
    void savedQueryOwnerMustHaveMembershipInTheTargetWorkspace() {
        UUID member = insertUser("ACTIVE");
        UUID outsider = insertUser("ACTIVE");
        UUID workspace = insertWorkspace("saved-query-membership");
        insertMembership(workspace, member, "OWNER", "ACTIVE");

        SavedQueryService savedQueries = new SavedQueryService(new JdbcSavedQueryRepository(jdbc));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> savedQueries.create(workspace, outsider, "Not allowed", "", "SELECT 1")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void insertHistory(UUID workspaceId, UUID userId, String sql, String createdAt) {
        jdbc.update("""
                INSERT INTO neurosql_app.query_history
                    (history_id, workspace_id, user_id, query_text, query_hash,
                     outcome, row_count, execution_time_ms, truncated, created_at)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', 1, 2, false, ?)
                """,
                UUID.randomUUID(),
                workspaceId,
                userId,
                sql,
                QueryHistoryService.sha256(sql),
                Timestamp.from(Instant.parse(createdAt)));
    }

    private UUID insertUser(String status) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO neurosql_app.app_users (user_id, status) VALUES (?, ?)", userId, status);
        return userId;
    }

    private UUID insertWorkspace(String name) {
        UUID workspaceId = UUID.randomUUID();
        String slug = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO neurosql_app.workspaces (workspace_id, name, slug) VALUES (?, ?, ?)",
                workspaceId,
                name,
                slug);
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
