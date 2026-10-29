-- CI contract test for least-privilege durable-auth grants.
-- Runs only against the disposable PostgreSQL 17 database in GitHub Actions.
-- Never point this script at Neon production.

DO $$
DECLARE
    role_ok BOOLEAN;
BEGIN
    SELECT NOT (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole
                OR rolreplication OR rolbypassrls OR rolinherit)
      INTO role_ok
      FROM pg_roles
     WHERE rolname = 'neurosql_auth_runtime';

    IF role_ok IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Runtime group role is missing or has unsafe role attributes';
    END IF;

    IF NOT has_schema_privilege('neurosql_auth_runtime', 'neurosql_app', 'USAGE')
       OR has_schema_privilege('neurosql_auth_runtime', 'neurosql_app', 'CREATE') THEN
        RAISE EXCEPTION 'Runtime schema privileges are not least-privileged';
    END IF;

    IF NOT has_column_privilege('neurosql_auth_runtime', 'neurosql_app.app_users', 'email', 'SELECT')
       OR NOT has_column_privilege('neurosql_auth_runtime', 'neurosql_app.app_users', 'password_hash', 'SELECT')
       OR has_column_privilege('neurosql_auth_runtime', 'neurosql_app.app_users', 'display_name', 'SELECT')
       OR has_column_privilege('neurosql_auth_runtime', 'neurosql_app.app_users', 'last_login_at', 'SELECT') THEN
        RAISE EXCEPTION 'Account column grants do not match the login query needs';
    END IF;

    IF NOT has_column_privilege('neurosql_auth_runtime', 'neurosql_app.workspaces', 'name', 'SELECT')
       OR has_column_privilege('neurosql_auth_runtime', 'neurosql_app.workspaces', 'created_by_user_id', 'SELECT')
       OR NOT has_column_privilege('neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'role', 'SELECT')
       OR has_column_privilege('neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'invited_by_user_id', 'SELECT') THEN
        RAISE EXCEPTION 'Workspace column grants do not match authorization query needs';
    END IF;

    IF NOT has_column_privilege('neurosql_auth_runtime', 'neurosql_app.user_sessions', 'token_hash', 'SELECT')
       OR NOT has_column_privilege('neurosql_auth_runtime', 'neurosql_app.user_sessions', 'user_id', 'INSERT')
       OR NOT has_column_privilege('neurosql_auth_runtime', 'neurosql_app.user_sessions', 'revoked_at', 'UPDATE')
       OR has_column_privilege('neurosql_auth_runtime', 'neurosql_app.user_sessions', 'token_hash', 'UPDATE')
       OR has_table_privilege('neurosql_auth_runtime', 'neurosql_app.user_sessions', 'DELETE')
       OR has_table_privilege('neurosql_auth_runtime', 'neurosql_app.workspace_memberships', 'INSERT') THEN
        RAISE EXCEPTION 'Session grants are too broad or incomplete';
    END IF;
END
$$;

BEGIN;

INSERT INTO neurosql_app.app_users
    (user_id, email, password_hash, display_name, status, email_verified_at)
VALUES
    ('00000000-0000-4000-8000-000000000001',
     'least-privilege@example.test', repeat('a', 60), 'Grant Probe', 'ACTIVE', now());

INSERT INTO neurosql_app.workspaces
    (workspace_id, name, slug, created_by_user_id)
VALUES
    ('00000000-0000-4000-8000-000000000002',
     'Grant Probe Workspace', 'grant-probe-workspace',
     '00000000-0000-4000-8000-000000000001');

INSERT INTO neurosql_app.workspace_memberships
    (workspace_id, user_id, role, status)
VALUES
    ('00000000-0000-4000-8000-000000000002',
     '00000000-0000-4000-8000-000000000001', 'OWNER', 'ACTIVE');

SET LOCAL ROLE neurosql_auth_runtime;

DO $$
DECLARE
    found_user UUID;
    found_hash TEXT;
    found_workspace UUID;
    found_role TEXT;
    found_session UUID;
    changed_rows INTEGER;
BEGIN
    SELECT user_id, password_hash
      INTO found_user, found_hash
      FROM neurosql_app.app_users
     WHERE lower(email) = 'least-privilege@example.test'
       AND status = 'ACTIVE'
       AND email_verified_at IS NOT NULL;

    IF found_user IS NULL OR length(found_hash) < 50 THEN
        RAISE EXCEPTION 'Runtime role could not perform the account lookup used by login';
    END IF;

    SELECT w.workspace_id, m.role
      INTO found_workspace, found_role
      FROM neurosql_app.workspace_memberships m
      JOIN neurosql_app.workspaces w ON w.workspace_id = m.workspace_id
      JOIN neurosql_app.app_users u ON u.user_id = m.user_id
     WHERE m.user_id = found_user
       AND m.status = 'ACTIVE'
       AND u.status = 'ACTIVE';

    IF found_workspace IS NULL OR found_role <> 'OWNER' THEN
        RAISE EXCEPTION 'Runtime role could not perform workspace authorization lookup';
    END IF;

    INSERT INTO neurosql_app.user_sessions (user_id, token_hash, created_at, expires_at)
    VALUES (found_user, repeat('a', 64), now(), now() + interval '1 hour')
    RETURNING session_id INTO found_session;

    IF NOT EXISTS (
        SELECT 1 FROM neurosql_app.user_sessions
         WHERE session_id = found_session
           AND token_hash = repeat('a', 64)
           AND revoked_at IS NULL
    ) THEN
        RAISE EXCEPTION 'Runtime role could not read its newly issued session';
    END IF;

    UPDATE neurosql_app.user_sessions
       SET revoked_at = GREATEST(now(), created_at)
     WHERE session_id = found_session
       AND token_hash = repeat('a', 64)
       AND revoked_at IS NULL;
    GET DIAGNOSTICS changed_rows = ROW_COUNT;

    IF changed_rows <> 1 OR NOT EXISTS (
        SELECT 1 FROM neurosql_app.user_sessions
         WHERE session_id = found_session AND revoked_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Runtime role could not revoke a session safely';
    END IF;
END
$$;

ROLLBACK;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM neurosql_app.app_users WHERE email = 'least-privilege@example.test')
       OR EXISTS (SELECT 1 FROM neurosql_app.workspaces WHERE slug = 'grant-probe-workspace')
       OR EXISTS (SELECT 1 FROM neurosql_app.user_sessions WHERE token_hash = repeat('a', 64)) THEN
        RAISE EXCEPTION 'Least-privilege grant test left fixture rows behind';
    END IF;
END
$$;

SELECT 'Least-privilege runtime grant contract passed; fixtures rolled back.' AS result;
