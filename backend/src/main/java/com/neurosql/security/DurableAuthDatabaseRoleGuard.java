package com.neurosql.security;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Refuses to activate durable authentication with the database owner or a
 * broadly privileged/incorrectly delegated login identity.
 *
 * <p>The durable-auth repositories use the application datasource. When the
 * feature is enabled, that datasource must authenticate as one dedicated
 * LOGIN role whose only membership is the non-login
 * {@code neurosql_auth_runtime} group. The membership must inherit privileges
 * but must not permit SET ROLE or delegation.</p>
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "neurosql.auth",
        name = "durable-sessions-enabled",
        havingValue = "true")
public final class DurableAuthDatabaseRoleGuard implements ApplicationRunner {
    static final String VALIDATE_RUNTIME_IDENTITY_SQL = """
            SELECT (
                r.rolcanlogin
                AND r.rolinherit
                AND NOT r.rolsuper
                AND NOT r.rolcreatedb
                AND NOT r.rolcreaterole
                AND NOT r.rolreplication
                AND NOT r.rolbypassrls
                AND current_user = session_user
                AND pg_has_role(session_user, 'neurosql_auth_runtime', 'MEMBER')
                AND pg_has_role(session_user, 'neurosql_auth_runtime', 'USAGE')
                AND NOT pg_has_role(session_user, 'neurosql_auth_runtime', 'SET')
                AND has_schema_privilege(session_user, 'neurosql_app', 'USAGE')
                AND NOT has_schema_privilege(session_user, 'neurosql_app', 'CREATE')
                AND (
                    SELECT count(*)
                    FROM pg_auth_members m
                    WHERE m.member = r.oid
                ) = 1
                AND EXISTS (
                    SELECT 1
                    FROM pg_auth_members m
                    JOIN pg_roles g ON g.oid = m.roleid
                    WHERE m.member = r.oid
                      AND g.rolname = 'neurosql_auth_runtime'
                      AND m.inherit_option
                      AND NOT m.set_option
                      AND NOT m.admin_option
                )
                AND EXISTS (
                    SELECT 1
                    FROM pg_roles g
                    WHERE g.rolname = 'neurosql_auth_runtime'
                      AND NOT g.rolcanlogin
                      AND NOT g.rolsuper
                      AND NOT g.rolcreatedb
                      AND NOT g.rolcreaterole
                      AND NOT g.rolreplication
                      AND NOT g.rolbypassrls
                )
            ) AS safe
            FROM pg_roles r
            WHERE r.rolname = session_user
            """;

    private static final String UNSAFE_IDENTITY_MESSAGE =
            "Durable authentication requires DATABASE_USERNAME to be a dedicated, "
                    + "non-privileged LOGIN with exactly one inherited-only, non-delegable "
                    + "membership in neurosql_auth_runtime. The database owner is not a runtime identity.";

    private final JdbcTemplate jdbcTemplate;

    public DurableAuthDatabaseRoleGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        final Boolean safe;
        try {
            safe = jdbcTemplate.queryForObject(VALIDATE_RUNTIME_IDENTITY_SQL, Boolean.class);
        } catch (DataAccessException invalidDatabaseIdentity) {
            throw new IllegalStateException(UNSAFE_IDENTITY_MESSAGE, invalidDatabaseIdentity);
        }

        if (!Boolean.TRUE.equals(safe)) {
            throw new IllegalStateException(UNSAFE_IDENTITY_MESSAGE);
        }
    }
}
