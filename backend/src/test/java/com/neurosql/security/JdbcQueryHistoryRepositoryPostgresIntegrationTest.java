package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Exercises query-history outcome filtering against the disposable PostgreSQL
 * 17 database supplied by CI. The URL guard prevents Neon or production use.
 */
@EnabledIfEnvironmentVariable(
        named = "NEUROSQL_TEST_JDBC_URL",
        matches = "^jdbc:postgresql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/neurosql_test(\\?.*)?$")
class JdbcQueryHistoryRepositoryPostgresIntegrationTest {
    private JdbcTemplate jdbc;
    private JdbcQueryHistoryRepository repository;
    private final List<UUID> insertedHistoryIds = new ArrayList<>();
    private final List<UUID> insertedUserIds = new ArrayList<>();
    private final List<UUID> insertedWorkspaceIds = new ArrayList<>();

    @BeforeEach
    void setUpDisposablePostgresFixture() {
        String url = requiredEnvironment("NEUROSQL_TEST_JDBC_URL");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(requiredEnvironment("NEUROSQL_TEST_DB_USERNAME"));
        dataSource.setPassword(requiredEnvironment("NEUROSQL_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);

        if (!"neurosql_test".equals(jdbc.queryForObject("SELECT current_database()", String.class))) {
            throw new IllegalStateException("Query-history integration tests only permit neurosql_test.");
        }

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
        repository = new JdbcQueryHistoryRepository(jdbc);
    }

    @AfterEach
    void removeOnlyThisTestClassFixtures() {
        if (jdbc != null) {
            for (UUID historyId : insertedHistoryIds) {
                jdbc.update("DELETE FROM neurosql_app.query_history WHERE history_id = ?", historyId);
            }
            for (UUID workspaceId : insertedWorkspaceIds) {
                jdbc.update("DELETE FROM neurosql_app.workspace_memberships WHERE workspace_id = ?", workspaceId);
                jdbc.update("DELETE FROM neurosql_app.workspaces WHERE workspace_id = ?", workspaceId);
            }
            for (UUID userId : insertedUserIds) {
                jdbc.update("DELETE FROM neurosql_app.workspace_memberships WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM neurosql_app.app_users WHERE user_id = ?", userId);
            }
        }
    }

    @Test
    void filtersOutcomeWithoutCrossingUserOrWorkspaceBoundaries() {
        UUID workspaceId = insertWorkspace();
        UUID otherWorkspaceId = insertWorkspace();
        UUID userId = insertUser();
        UUID otherUserId = insertUser();
        insertMembership(workspaceId, userId);
        insertMembership(workspaceId, otherUserId);
        insertMembership(otherWorkspaceId, userId);
        Instant now = Instant.parse("2026-11-02T10:00:00Z");

        insert(workspaceId, userId, "FAILED", "SELECT broken", now);
        insert(workspaceId, userId, "SUCCEEDED", "SELECT 1", now.minusSeconds(1));
        insert(workspaceId, userId, "CANCELED", "SELECT pg_sleep(1)", now.minusSeconds(2));
        insert(workspaceId, otherUserId, "FAILED", "SELECT other user", now.minusSeconds(3));
        insert(otherWorkspaceId, userId, "FAILED", "SELECT other workspace", now.minusSeconds(4));

        List<JdbcQueryHistoryRepository.HistoryEntry> failed =
                repository.findRecent(workspaceId, userId, 10, null, "FAILED");

        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).queryText()).isEqualTo("SELECT broken");
        assertThat(failed.get(0).outcome()).isEqualTo("FAILED");
    }

    @Test
    void appliesOutcomeFilterBeforeKeysetLimitAcrossPages() {
        UUID workspaceId = insertWorkspace();
        UUID userId = insertUser();
        insertMembership(workspaceId, userId);
        Instant newest = Instant.parse("2026-11-02T11:00:00Z");

        insert(workspaceId, userId, "SUCCEEDED", "SELECT newest success", newest.plusSeconds(1));
        insert(workspaceId, userId, "FAILED", "SELECT failed one", newest);
        insert(workspaceId, userId, "FAILED", "SELECT failed two", newest.minusSeconds(1));
        insert(workspaceId, userId, "FAILED", "SELECT failed three", newest.minusSeconds(2));

        List<JdbcQueryHistoryRepository.HistoryEntry> first =
                repository.findRecent(workspaceId, userId, 2, null, "FAILED");
        assertThat(first).extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT failed one", "SELECT failed two");

        JdbcQueryHistoryRepository.HistoryEntry lastVisible = first.get(1);
        QueryHistoryCursor.Position cursor =
                new QueryHistoryCursor.Position(lastVisible.createdAt(), lastVisible.historyId());
        List<JdbcQueryHistoryRepository.HistoryEntry> second =
                repository.findRecent(workspaceId, userId, 2, cursor, "FAILED");

        assertThat(second).extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT failed three");
    }

    @Test
    void appliesInclusiveUtcDateRangeAtBothBoundaries() {
        UUID workspaceId=insertWorkspace();
        UUID userId=insertUser();
        insertMembership(workspaceId,userId);
        insert(workspaceId,userId,"SUCCEEDED","SELECT end boundary",Instant.parse("2026-11-02T23:59:59.999999Z"));
        insert(workspaceId,userId,"SUCCEEDED","SELECT start boundary",Instant.parse("2026-11-01T00:00:00Z"));
        insert(workspaceId,userId,"SUCCEEDED","SELECT before range",Instant.parse("2026-10-31T23:59:59.999999Z"));
        insert(workspaceId,userId,"SUCCEEDED","SELECT after range",Instant.parse("2026-11-03T00:00:00Z"));
        List<JdbcQueryHistoryRepository.HistoryEntry> entries=repository.findRecent(
                workspaceId,userId,10,null,null,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-02"));
        assertThat(entries).extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT end boundary","SELECT start boundary");
    }

    @Test
    void searchesSqlTextCaseInsensitivelyAndTreatsWildcardCharactersLiterallyWithinScope() {
        UUID workspaceId = insertWorkspace();
        UUID otherWorkspaceId = insertWorkspace();
        UUID userId = insertUser();
        UUID otherUserId = insertUser();
        insertMembership(workspaceId, userId);
        insertMembership(workspaceId, otherUserId);
        insertMembership(otherWorkspaceId, userId);
        Instant now = Instant.parse("2026-11-02T13:00:00Z");

        insert(workspaceId, userId, "SUCCEEDED",
                "SELECT * FROM Finance_Orders WHERE code LIKE 'A%_B'", now);
        insert(workspaceId, userId, "SUCCEEDED",
                "SELECT * FROM finance_accounts", now.minusSeconds(1));
        insert(workspaceId, otherUserId, "SUCCEEDED",
                "SELECT * FROM finance_orders for another user", now.minusSeconds(2));
        insert(otherWorkspaceId, userId, "SUCCEEDED",
                "SELECT * FROM finance_orders for another workspace", now.minusSeconds(3));

        List<JdbcQueryHistoryRepository.HistoryEntry> caseInsensitive =
                repository.findRecent(workspaceId, userId, 10, null, null, null, null, "FINANCE_ORDERS");
        assertThat(caseInsensitive).hasSize(1);
        assertThat(caseInsensitive.get(0).queryText()).contains("Finance_Orders");

        List<JdbcQueryHistoryRepository.HistoryEntry> literalWildcard =
                repository.findRecent(workspaceId, userId, 10, null, null, null, null, "%_B");
        assertThat(literalWildcard).hasSize(1);
        assertThat(literalWildcard.get(0).queryText()).contains("A%_B");
    }

    @Test
    void appliesSqlTextSearchBeforeKeysetLimitAcrossPages() {
        UUID workspaceId = insertWorkspace();
        UUID userId = insertUser();
        insertMembership(workspaceId, userId);
        Instant newest = Instant.parse("2026-11-02T14:00:00Z");

        insert(workspaceId, userId, "SUCCEEDED", "SELECT newest unrelated", newest.plusSeconds(1));
        insert(workspaceId, userId, "FAILED", "SELECT needle one", newest);
        insert(workspaceId, userId, "FAILED", "SELECT needle two", newest.minusSeconds(1));
        insert(workspaceId, userId, "FAILED", "SELECT needle three", newest.minusSeconds(2));

        List<JdbcQueryHistoryRepository.HistoryEntry> first =
                repository.findRecent(workspaceId, userId, 2, null, "FAILED", null, null, "NEEDLE");
        assertThat(first).extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT needle one", "SELECT needle two");

        var lastVisible = first.get(1);
        QueryHistoryCursor.Position cursor =
                new QueryHistoryCursor.Position(lastVisible.createdAt(), lastVisible.historyId());
        List<JdbcQueryHistoryRepository.HistoryEntry> second =
                repository.findRecent(workspaceId, userId, 2, cursor, "FAILED", null, null, "needle");

        assertThat(second).extracting(JdbcQueryHistoryRepository.HistoryEntry::queryText)
                .containsExactly("SELECT needle three");
    }

    @Test
    void nullOutcomeReturnsAllSupportedOutcomesInDescendingOrder() {
        UUID workspaceId = insertWorkspace();
        UUID userId = insertUser();
        insertMembership(workspaceId, userId);
        Instant newest = Instant.parse("2026-11-02T12:00:00Z");

        insert(workspaceId, userId, "CANCELED", "SELECT canceled", newest);
        insert(workspaceId, userId, "FAILED", "SELECT failed", newest.minusSeconds(1));
        insert(workspaceId, userId, "SUCCEEDED", "SELECT succeeded", newest.minusSeconds(2));

        List<JdbcQueryHistoryRepository.HistoryEntry> all =
                repository.findRecent(workspaceId, userId, 10, null, null);

        assertThat(all).extracting(JdbcQueryHistoryRepository.HistoryEntry::outcome)
                .containsExactly("CANCELED", "FAILED", "SUCCEEDED");
    }

    private UUID insertUser() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO neurosql_app.app_users (user_id, status) VALUES (?, 'ACTIVE')", userId);
        insertedUserIds.add(userId);
        return userId;
    }

    private UUID insertWorkspace() {
        UUID workspaceId = UUID.randomUUID();
        String slug = "history-filter-" + workspaceId;
        jdbc.update(
                "INSERT INTO neurosql_app.workspaces (workspace_id, name, slug) VALUES (?, ?, ?)",
                workspaceId,
                "History filter " + workspaceId,
                slug);
        insertedWorkspaceIds.add(workspaceId);
        return workspaceId;
    }

    private void insertMembership(UUID workspaceId, UUID userId) {
        jdbc.update("""
                INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role, status)
                VALUES (?, ?, 'MEMBER', 'ACTIVE')
                """,
                workspaceId,
                userId);
    }

    private void insert(UUID workspaceId, UUID userId, String outcome, String sql, Instant createdAt) {
        Integer rowCount = "SUCCEEDED".equals(outcome) ? 1 : null;
        UUID historyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO neurosql_app.query_history
                    (history_id, workspace_id, user_id, query_text, query_hash, outcome,
                     row_count, execution_time_ms, truncated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                historyId,
                workspaceId,
                userId,
                sql,
                "a".repeat(64),
                outcome,
                rowCount,
                3,
                false,
                Timestamp.from(createdAt));
        insertedHistoryIds.add(historyId);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the disposable PostgreSQL integration test.");
        }
        return value;
    }
}
