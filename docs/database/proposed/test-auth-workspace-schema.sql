-- CI contract test for the proposed durable-auth schema.
-- This file runs only against a disposable PostgreSQL database in GitHub Actions.
-- It is not an application migration and must never be pointed at Neon production.

BEGIN;

DO $$
DECLARE
    user_a UUID;
    user_b UUID;
    workspace_a UUID;
BEGIN
    IF (SELECT count(*) FROM information_schema.tables
        WHERE table_schema = 'neurosql_app' AND table_type = 'BASE TABLE') <> 6 THEN
        RAISE EXCEPTION 'Expected exactly six proposed application tables';
    END IF;

    IF (SELECT count(*) FROM pg_indexes WHERE schemaname = 'neurosql_app') <> 16 THEN
        RAISE EXCEPTION 'Expected exactly sixteen proposed indexes';
    END IF;

    IF (SELECT count(*)
        FROM pg_trigger t
        JOIN pg_class c ON c.oid = t.tgrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'neurosql_app'
          AND t.tgname IN ('ct_workspaces_require_active_owner', 'ct_memberships_require_active_owner')
          AND NOT t.tgisinternal) <> 2 THEN
        RAISE EXCEPTION 'Expected both deferred active-owner constraint triggers';
    END IF;

    INSERT INTO neurosql_app.app_users (email, password_hash, display_name)
    VALUES ('alice@example.test', repeat('a', 60), 'Alice')
    RETURNING user_id INTO user_a;

    INSERT INTO neurosql_app.app_users (email, password_hash, display_name)
    VALUES ('bob@example.test', repeat('b', 60), 'Bob')
    RETURNING user_id INTO user_b;

    BEGIN
        INSERT INTO neurosql_app.app_users (email, password_hash, display_name)
        VALUES ('ALICE@example.test', repeat('c', 60), 'Duplicate Alice');
        RAISE EXCEPTION 'Expected case-insensitive duplicate email to be rejected';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    INSERT INTO neurosql_app.workspaces (name, slug, created_by_user_id)
    VALUES ('Team One', 'team-one', user_a)
    RETURNING workspace_id INTO workspace_a;

    BEGIN
        INSERT INTO neurosql_app.workspaces (name, slug, created_by_user_id)
        VALUES ('Duplicate Team', 'team-one', user_b);
        RAISE EXCEPTION 'Expected duplicate workspace slug to be rejected';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role)
    VALUES (workspace_a, user_a, 'OWNER');

    BEGIN
        INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role)
        VALUES (workspace_a, user_b, 'OWNER');
        RAISE EXCEPTION 'Expected a second active owner to be rejected';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role)
        VALUES (workspace_a, user_b, 'SUPERUSER');
        RAISE EXCEPTION 'Expected an invalid membership role to be rejected';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO neurosql_app.user_sessions (user_id, token_hash, expires_at)
        VALUES (user_a, 'not-a-sha256-hex-digest', now() + interval '1 hour');
        RAISE EXCEPTION 'Expected an invalid session token hash to be rejected';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role)
        VALUES (workspace_a, gen_random_uuid(), 'MEMBER');
        RAISE EXCEPTION 'Expected an unknown membership user to be rejected';
    EXCEPTION WHEN foreign_key_violation THEN
        NULL;
    END;

    INSERT INTO neurosql_app.user_sessions (user_id, token_hash, expires_at)
    VALUES (user_a, repeat('a', 64), now() + interval '1 hour');

    -- Transfer ownership in one transaction: the deferred invariant sees the final owner.
    UPDATE neurosql_app.workspace_memberships
       SET status = 'SUSPENDED'
     WHERE workspace_id = workspace_a AND user_id = user_a AND role = 'OWNER';
    INSERT INTO neurosql_app.workspace_memberships (workspace_id, user_id, role, status)
    VALUES (workspace_a, user_b, 'OWNER', 'ACTIVE');
    SET CONSTRAINTS ALL IMMEDIATE;

    -- A workspace cannot be created without an active owner.
    BEGIN
        INSERT INTO neurosql_app.workspaces (name, slug, created_by_user_id)
        VALUES ('Ownerless Team', 'ownerless-team', user_a);
        RAISE EXCEPTION 'Expected ownerless workspace creation to be rejected';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    -- Removing the final active owner must fail; the subtransaction rolls back the deletion.
    BEGIN
        DELETE FROM neurosql_app.workspace_memberships
         WHERE workspace_id = workspace_a AND user_id = user_b
           AND role = 'OWNER' AND status = 'ACTIVE';
        RAISE EXCEPTION 'Expected removal of the final active owner to be rejected';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    RAISE NOTICE 'Schema contract checks passed';
END
$$;

-- Roll back all fixture rows so the test leaves no persisted data.
ROLLBACK;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM neurosql_app.app_users)
       OR EXISTS (SELECT 1 FROM neurosql_app.workspaces)
       OR EXISTS (SELECT 1 FROM neurosql_app.workspace_memberships)
       OR EXISTS (SELECT 1 FROM neurosql_app.user_sessions)
       OR EXISTS (SELECT 1 FROM neurosql_app.auth_login_rate_limits)
       OR EXISTS (SELECT 1 FROM neurosql_app.account_action_tokens) THEN
        RAISE EXCEPTION 'Schema contract test left fixture rows behind';
    END IF;
END
$$;

SELECT 'Proposed auth/workspace schema contract passed; no fixture rows remain.' AS result;
