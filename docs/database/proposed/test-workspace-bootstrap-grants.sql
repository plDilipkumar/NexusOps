-- CI contract for the V8 personal-workspace bootstrap grants.
-- Runs only against the disposable PostgreSQL 17 schema-contract database.
-- Never point this script at Neon production.

DO $$
BEGIN
    IF NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspaces', 'name', 'INSERT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspaces', 'slug', 'INSERT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspaces', 'created_by_user_id', 'INSERT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'workspace_id', 'INSERT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'user_id', 'INSERT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'role', 'INSERT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'status', 'INSERT') THEN
        RAISE EXCEPTION 'Auth runtime cannot create a personal workspace and owner membership';
    END IF;

    IF has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspaces', 'created_by_user_id', 'UPDATE')
       OR has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspaces', 'DELETE')
       OR has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'role', 'UPDATE')
       OR has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'user_id', 'UPDATE')
       OR has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'DELETE') THEN
        RAISE EXCEPTION 'Auth runtime can mutate workspace ownership or membership after bootstrap';
    END IF;

    IF has_schema_privilege('neurosql_auth_runtime', 'neurosql_app', 'CREATE') THEN
        RAISE EXCEPTION 'Auth runtime unexpectedly has schema CREATE';
    END IF;
END
$$;

BEGIN;

INSERT INTO neurosql_app.app_users
    (user_id, email, password_hash, display_name, status)
VALUES
    ('00000000-0000-4000-8000-000000000008',
     'workspace-bootstrap@example.test', repeat('a', 60), 'Workspace Bootstrap', 'ACTIVE');

SET LOCAL ROLE neurosql_auth_runtime;

DO $$
DECLARE
    created_workspace UUID;
BEGIN
    INSERT INTO neurosql_app.workspaces (name, slug, created_by_user_id)
    VALUES (
        'Workspace Bootstrap Workspace',
        'bootstrap-probe-workspace',
        '00000000-0000-4000-8000-000000000008'
    )
    RETURNING workspace_id INTO created_workspace;

    INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role, status)
    VALUES (
        created_workspace,
        '00000000-0000-4000-8000-000000000008',
        'OWNER',
        'ACTIVE'
    );

    IF NOT EXISTS (
        SELECT 1
          FROM neurosql_app.workspace_memberships
         WHERE workspace_id = created_workspace
           AND user_id = '00000000-0000-4000-8000-000000000008'
           AND role = 'OWNER'
           AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Auth runtime could not read the created owner membership';
    END IF;
END
$$;

-- Force the deferred "every workspace has an active owner" trigger before rollback.
SET CONSTRAINTS ALL IMMEDIATE;
ROLLBACK;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM neurosql_app.app_users
         WHERE email = 'workspace-bootstrap@example.test'
    ) OR EXISTS (
        SELECT 1 FROM neurosql_app.workspaces
         WHERE slug = 'bootstrap-probe-workspace'
    ) OR EXISTS (
        SELECT 1 FROM neurosql_app.workspace_memberships
         WHERE user_id = '00000000-0000-4000-8000-000000000008'
    ) THEN
        RAISE EXCEPTION 'Workspace bootstrap contract left fixture rows behind';
    END IF;
END
$$;

SELECT 'Personal workspace bootstrap grant contract passed; fixtures rolled back.' AS result;
