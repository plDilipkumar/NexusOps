package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class SharedLoginRateLimitHealthIndicatorTest {
    private static final String EXPECTED_DATABASE = "neurosql_db";

    @Test
    void reportsUpWhenExpectedDatabaseAndAllRequiredLeastPrivilegeGrantsArePresent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(readyRow(EXPECTED_DATABASE));

        Health health = indicator(jdbc, true, true).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        verify(jdbc).queryForMap(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.startsWith("SELECT")
                        && sql.contains("current_database()")
                        && sql.contains("has_column_privilege")
                        && sql.contains("has_table_privilege")
                        && !sql.contains("INSERT INTO")
                        && !sql.contains("UPDATE ")
                        && !sql.contains("DELETE FROM")));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void reportsDownWhenARequiredColumnGrantIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> result = readyRow(EXPECTED_DATABASE);
        result.put("can_update_failure_timestamps", false);
        when(jdbc.queryForMap(anyString())).thenReturn(result);

        assertThat(indicator(jdbc, true, true).health().getStatus()).isEqualTo(Status.DOWN);
        verify(jdbc).queryForMap(anyString());
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void reportsDownWhenRuntimeRoleCanModifyIdentityHashOrTruncateTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> result = readyRow(EXPECTED_DATABASE);
        result.put("identity_hash_update_denied", false);
        when(jdbc.queryForMap(anyString())).thenReturn(result);

        assertThat(indicator(jdbc, true, true).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenConnectedDatabaseIsNotTheDedicatedNeuroSqlDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(readyRow("another_project_db"));

        assertThat(indicator(jdbc, true, true).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWithoutLeakingDatabaseErrorsWhenProbeFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString()))
                .thenThrow(new DataAccessResourceFailureException("sensitive connection details"));

        Health health = indicator(jdbc, true, true).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }

    @Test
    void reportsDisabledAsUpWithoutConnectingWhenBothFlagsAreOff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(indicator(jdbc, false, false).health().getStatus()).isEqualTo(Status.UP);
        verifyNoInteractions(jdbc);
    }

    @Test
    void reportsDownForInconsistentFeatureFlagsWithoutConnecting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(indicator(jdbc, true, false).health().getStatus()).isEqualTo(Status.DOWN);
        verifyNoInteractions(jdbc);
    }

    private static SharedLoginRateLimitHealthIndicator indicator(
            JdbcTemplate jdbc, boolean durableSessionsEnabled, boolean sharedRateLimitEnabled) {
        return new SharedLoginRateLimitHealthIndicator(
                jdbc, durableSessionsEnabled, sharedRateLimitEnabled, EXPECTED_DATABASE);
    }

    private static Map<String, Object> readyRow(String databaseName) {
        return new HashMap<>(Map.ofEntries(
                Map.entry("database_name", databaseName),
                Map.entry("table_exists", true),
                Map.entry("schema_usage", true),
                Map.entry("can_select_identity_hash", true),
                Map.entry("can_select_failure_timestamps", true),
                Map.entry("can_select_blocked_until", true),
                Map.entry("can_select_updated_at", true),
                Map.entry("can_insert_identity_hash", true),
                Map.entry("can_insert_failure_timestamps", true),
                Map.entry("can_insert_blocked_until", true),
                Map.entry("can_insert_updated_at", true),
                Map.entry("can_update_failure_timestamps", true),
                Map.entry("can_update_blocked_until", true),
                Map.entry("can_update_updated_at", true),
                Map.entry("can_delete_rows", true),
                Map.entry("identity_hash_update_denied", true),
                Map.entry("truncate_denied", true)));
    }
}
