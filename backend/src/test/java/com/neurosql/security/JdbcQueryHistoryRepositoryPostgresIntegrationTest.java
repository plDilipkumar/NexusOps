package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        repository = new JdbcQueryHistoryRepository(jdbc);
    }

    @Test
    void filtersOutcomeWithoutCrossingUserOrWorkspaceBoundaries() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-02T10:00:00Z");

        insert(workspaceId, userId, "FAILED", "SELECT broken", now);
        insert(workspaceId, userId, "SUCCEEDED", "SELECT 1", now.minusSeconds(1));
        insert(workspaceId, userId, "CANCELED", "SELECT pg_sleep(1)", now.minusSeconds(2));
        insert(workspaceId, UUID.randomUUID(), "FAILED", "SELECT other user", now.minusSeconds(3));
        insert(UUID.randomUUID(), userId, "FAILED", "SELECT other workspace", now.minusSeconds(4));

        List<JdbcQueryHistoryRepository.HistoryEntry> failed =
                repository.findRecent(workspaceId, userId, 10, null, "FAILED");

        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).queryText()).isEqualTo("SELECT broken");
        assertThat(failed.get(0).outcome()).isEqualTo("FAILED");
    }

    @Test
    void appliesOutcomeFilterBeforeKeysetLimitAcrossPages() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
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
    void nullOutcomeReturnsAllSupportedOutcomesInDescendingOrder() {
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant newest = Instant.parse("2026-11-02T12:00:00Z");

        insert(workspaceId, userId, "CANCELED", "SELECT canceled", newest);
        insert(workspaceId, userId, "FAILED", "SELECT failed", newest.minusSeconds(1));
        insert(workspaceId, userId, "SUCCEEDED", "SELECT succeeded", newest.minusSeconds(2));

        List<JdbcQueryHistoryRepository.HistoryEntry> all =
                repository.findRecent(workspaceId, userId, 10, null, null);

        assertThat(all).extracting(JdbcQueryHistoryRepository.HistoryEntry::outcome)
                .containsExactly("CANCELED", "FAILED", "SUCCEEDED");
    }

    private void insert(UUID workspaceId, UUID userId, String outcome, String sql, Instant createdAt) {
        Integer rowCount = "SUCCEEDED".equals(outcome) ? 1 : null;
        jdbc.update("""
                INSERT INTO neurosql_app.query_history
                    (history_id, workspace_id, user_id, query_text, query_hash, outcome,
                     row_count, execution_time_ms, truncated, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                workspaceId,
                userId,
                sql,
                "a".repeat(64),
                outcome,
                rowCount,
                3,
                false,
                Timestamp.from(createdAt));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the disposable PostgreSQL integration test.");
        }
        return value;
    }
}
