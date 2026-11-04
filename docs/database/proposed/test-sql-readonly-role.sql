-- CI contract test for the isolated read-only SQL execution group.
-- Runs only against disposable PostgreSQL 17 or an isolated Neon review branch.
-- The temporary LOGIN role and probe schema are rolled back at the end.

DO $$
DECLARE
    role_is_safe BOOLEAN;
BEGIN
    SELECT NOT (
        rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole
        OR rolreplication OR rolbypassrls OR rolinherit
    )
      INTO role_is_safe
      FROM pg_roles
     WHERE rolname = 'neurosql_sql_readonly';

    IF role_is_safe IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Read-only SQL group role is missing or has unsafe attributes';
    END IF;

    IF NOT has_database_privilege('neurosql_sql_readonly', current_database(), 'CONNECT')
       OR has_database_privilege('neurosql_sql_readonly', current_database(), 'CREATE') THEN
        RAISE EXCEPTION 'Read-only SQL group database privileges are not least-privileged';
    END IF;

    IF has_schema_privilege('neurosql_sql_readonly', 'neurosql_app', 'USAGE')
       OR has_schema_privilege('neurosql_sql_readonly', 'neurosql_app', 'CREATE')
       OR has_table_privilege('neurosql_sql_readonly', 'neurosql_app.app_users', 'SELECT')
       OR has_table_privilege('neurosql_sql_readonly', 'neurosql_app.user_sessions', 'SELECT') THEN
        RAISE EXCEPTION 'Read-only SQL group can access protected authentication metadata';
    END IF;
END
$$;

BEGIN;

CREATE ROLE neurosql_sql_readonly_probe_login
    LOGIN
    INHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS;

GRANT neurosql_sql_readonly TO neurosql_sql_readonly_probe_login WITH INHERIT TRUE, SET FALSE;

CREATE SCHEMA neurosql_sql_probe;
CREATE TABLE neurosql_sql_probe.probe (
    id INTEGER PRIMARY KEY,
    value TEXT NOT NULL
);
INSERT INTO neurosql_sql_probe.probe (id, value) VALUES (1, 'read-only');

GRANT USAGE ON SCHEMA neurosql_sql_probe TO neurosql_sql_readonly;
GRANT SELECT ON TABLE neurosql_sql_probe.probe TO neurosql_sql_readonly;

SET LOCAL ROLE neurosql_sql_readonly_probe_login;

DO $$
DECLARE
    probe_value TEXT;
BEGIN
    SELECT value INTO probe_value
      FROM neurosql_sql_probe.probe
     WHERE id = 1;

    IF NOT pg_has_role(current_user, 'neurosql_sql_readonly', 'USAGE')
       OR pg_has_role(current_user, 'neurosql_sql_readonly', 'SET')
       OR NOT EXISTS (
           SELECT 1 FROM pg_auth_members membership
           WHERE membership.roleid = 'neurosql_sql_readonly'::regrole
             AND membership.member = current_user::regrole
             AND NOT membership.admin_option
             AND membership.inherit_option
             AND NOT membership.set_option
       ) THEN
        RAISE EXCEPTION 'Read-only SQL membership options are not least-privileged';
    END IF;

    IF probe_value <> 'read-only' THEN
        RAISE EXCEPTION 'Read-only SQL group could not read an explicitly granted table';
    END IF;

    BEGIN
        INSERT INTO neurosql_sql_probe.probe (id, value) VALUES (2, 'must-not-commit');
        RAISE EXCEPTION 'Read-only SQL group unexpectedly inserted a row';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    BEGIN
        UPDATE neurosql_sql_probe.probe SET value = 'must-not-update' WHERE id = 1;
        RAISE EXCEPTION 'Read-only SQL group unexpectedly updated a row';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    BEGIN
        DELETE FROM neurosql_sql_probe.probe WHERE id = 1;
        RAISE EXCEPTION 'Read-only SQL group unexpectedly deleted a row';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    BEGIN
        EXECUTE 'TRUNCATE TABLE neurosql_sql_probe.probe';
        RAISE EXCEPTION 'Read-only SQL group unexpectedly truncated a table';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    BEGIN
        EXECUTE 'CREATE TABLE neurosql_sql_probe.forbidden (id INTEGER)';
        RAISE EXCEPTION 'Read-only SQL group unexpectedly created a table';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
END
$$;

ROLLBACK;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = 'neurosql_sql_readonly_probe_login'
    ) OR EXISTS (
        SELECT 1 FROM pg_namespace WHERE nspname = 'neurosql_sql_probe'
    ) THEN
        RAISE EXCEPTION 'Read-only SQL role contract test left temporary objects behind';
    END IF;
END
$$;

SELECT 'Read-only SQL role contract passed; temporary login and probe schema rolled back.' AS result;
