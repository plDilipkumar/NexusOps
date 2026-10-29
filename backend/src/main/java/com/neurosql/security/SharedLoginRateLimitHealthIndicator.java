package com.neurosql.security;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only readiness check for the shared durable-login limiter.
 *
 * <p>When durable auth is enabled, readiness requires the dedicated database,
 * the proposed rate-limit table, the exact column privileges used by the JDBC
 * limiter, and the intended least-privilege boundaries. It never writes state
 * or exposes database/permission details in the health response.</p>
 */
@Component("sharedLoginRateLimit")
public class SharedLoginRateLimitHealthIndicator implements HealthIndicator {
    private static final String CHECK_SQL = """
            SELECT current_database() AS database_name,
                   to_regclass('neurosql_app.auth_login_rate_limits') IS NOT NULL AS table_exists,
                   has_schema_privilege(current_user, 'neurosql_app', 'USAGE') AS schema_usage,
                   NOT has_schema_privilege(current_user, 'neurosql_app', 'CREATE') AS schema_create_denied,
                   current_user <> pg_get_userbyid(
                       (SELECT datdba FROM pg_database WHERE datname = current_database())
                   ) AS not_database_owner,
                   NOT pg_has_role(
                       current_user,
                       (SELECT datdba FROM pg_database WHERE datname = current_database()),
                       'MEMBER'
                   ) AS not_database_owner_member,
                   NOT EXISTS (
                       SELECT 1 FROM pg_roles role_info
                       WHERE role_info.rolname = current_user
                         AND (role_info.rolsuper OR role_info.rolcreatedb
                              OR role_info.rolcreaterole OR role_info.rolreplication
                              OR role_info.rolbypassrls)
                   ) AS safe_role_attributes,
                   NOT EXISTS (
                       SELECT 1 FROM pg_roles privileged_role
                       WHERE (privileged_role.rolsuper OR privileged_role.rolcreatedb
                              OR privileged_role.rolcreaterole OR privileged_role.rolreplication
                              OR privileged_role.rolbypassrls)
                         AND pg_has_role(current_user, privileged_role.oid, 'MEMBER')
                   ) AS no_elevated_role_membership,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'identity_hash', 'SELECT') AS can_select_identity_hash,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'failure_timestamps', 'SELECT') AS can_select_failure_timestamps,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'blocked_until', 'SELECT') AS can_select_blocked_until,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'updated_at', 'SELECT') AS can_select_updated_at,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'identity_hash', 'INSERT') AS can_insert_identity_hash,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'failure_timestamps', 'INSERT') AS can_insert_failure_timestamps,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'blocked_until', 'INSERT') AS can_insert_blocked_until,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'updated_at', 'INSERT') AS can_insert_updated_at,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'failure_timestamps', 'UPDATE') AS can_update_failure_timestamps,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'blocked_until', 'UPDATE') AS can_update_blocked_until,
                   has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'updated_at', 'UPDATE') AS can_update_updated_at,
                   has_table_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'DELETE') AS can_delete_rows,
                   NOT has_column_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'identity_hash', 'UPDATE') AS identity_hash_update_denied,
                   NOT has_table_privilege(current_user, 'neurosql_app.auth_login_rate_limits', 'TRUNCATE') AS truncate_denied
            """;

    private static final List<String> REQUIRED_TRUE_COLUMNS = List.of(
            "table_exists",
            "schema_usage",
            "schema_create_denied",
            "not_database_owner",
            "not_database_owner_member",
            "safe_role_attributes",
            "no_elevated_role_membership",
            "can_select_identity_hash",
            "can_select_failure_timestamps",
            "can_select_blocked_until",
            "can_select_updated_at",
            "can_insert_identity_hash",
            "can_insert_failure_timestamps",
            "can_insert_blocked_until",
            "can_insert_updated_at",
            "can_update_failure_timestamps",
            "can_update_blocked_until",
            "can_update_updated_at",
            "can_delete_rows",
            "identity_hash_update_denied",
            "truncate_denied");

    private final JdbcTemplate jdbc;
    private final boolean durableSessionsEnabled;
    private final boolean sharedRateLimitEnabled;
    private final String expectedDatabase;

    public SharedLoginRateLimitHealthIndicator(
            JdbcTemplate jdbc,
            @Value("${neurosql.auth.durable-sessions-enabled:false}") boolean durableSessionsEnabled,
            @Value("${neurosql.auth.shared-rate-limit-enabled:false}") boolean sharedRateLimitEnabled,
            @Value("${neurosql.auth.expected-database:neurosql_db}") String expectedDatabase) {
        this.jdbc = jdbc;
        this.durableSessionsEnabled = durableSessionsEnabled;
        this.sharedRateLimitEnabled = sharedRateLimitEnabled;
        this.expectedDatabase = expectedDatabase;
    }

    @Override
    public Health health() {
        if (!durableSessionsEnabled && !sharedRateLimitEnabled) {
            return Health.up().withDetail("state", "disabled").build();
        }
        if (!durableSessionsEnabled || !sharedRateLimitEnabled) {
            return Health.down().build();
        }

        try {
            Map<String, Object> result = jdbc.queryForMap(CHECK_SQL);
            boolean expectedDatabaseSelected = expectedDatabase.equals(result.get("database_name"));
            boolean permissionsReady = REQUIRED_TRUE_COLUMNS.stream()
                    .allMatch(column -> Boolean.TRUE.equals(result.get(column)));
            return expectedDatabaseSelected && permissionsReady
                    ? Health.up().build()
                    : Health.down().build();
        } catch (DataAccessException ex) {
            // Do not expose schema, role, connection, or SQL details via public health endpoints.
            return Health.down().build();
        }
    }
}
