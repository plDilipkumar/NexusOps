-- PROPOSED ONLY: isolated read-only SQL execution identity.
-- Apply only after V1-V6 on a temporary Neon branch. Never apply to Neon main
-- or create a production LOGIN role without explicit user approval.
--
-- This creates only a NOLOGIN group. Production must use a separate LOGIN role
-- with a secret managed by Neon and stored only in Render environment variables.
-- Grant SELECT on explicitly approved user-data schemas/tables only. Never grant
-- this role access to neurosql_app, which contains authentication metadata.

CREATE ROLE neurosql_sql_readonly
    NOLOGIN
    NOINHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS;

DO $$
BEGIN
    EXECUTE format(
        'GRANT CONNECT ON DATABASE %I TO neurosql_sql_readonly',
        current_database()
    );
END
$$;

REVOKE ALL ON SCHEMA neurosql_app FROM neurosql_sql_readonly;
REVOKE ALL ON ALL TABLES IN SCHEMA neurosql_app FROM neurosql_sql_readonly;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA neurosql_app FROM neurosql_sql_readonly;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA neurosql_app FROM neurosql_sql_readonly;

COMMENT ON ROLE neurosql_sql_readonly IS
    'NOLOGIN group for bounded read-only SQL execution; grant SELECT only on explicitly approved data schemas.';
