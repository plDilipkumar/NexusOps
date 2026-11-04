-- Contract test for proposed V5 query history and saved-query schema.
-- Runs only on disposable PostgreSQL 17 in CI or an isolated temporary Neon branch.
-- Never run this file against Neon main.

DO $$
BEGIN
    IF (SELECT count(*) FROM information_schema.tables
        WHERE table_schema = 'neurosql_app' AND table_type = 'BASE TABLE') <> 8 THEN
        RAISE EXCEPTION 'Expected eight proposed application tables after V5';
    END IF;

    IF (SELECT count(*) FROM pg_indexes WHERE schemaname = 'neurosql_app') <> 22 THEN
        RAISE EXCEPTION 'Expected twenty-two proposed application indexes after V5';
    END IF;

    IF NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.query_history', 'query_text', 'SELECT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.query_history', 'query_text', 'INSERT')
       OR has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.query_history', 'query_text', 'UPDATE')
       OR has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.query_history', 'DELETE')
       OR has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.query_history', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Query-history grants are missing or excessive';
    END IF;

    IF NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.saved_queries', 'query_text', 'SELECT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.saved_queries', 'query_text', 'INSERT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.saved_queries', 'query_text', 'UPDATE')
       OR NOT has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.saved_queries', 'DELETE')
       OR has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.saved_queries', 'workspace_id', 'UPDATE')
       OR has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.saved_queries', 'owner_user_id', 'UPDATE')
       OR has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.saved_queries', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Saved-query grants are missing or excessive';
    END IF;
END
$$;

BEGIN;

INSERT INTO neurosql_app.app_users
    (user_id, email, password_hash, display_name, status, email_verified_at)
VALUES
    ('00000000-0000-4000-8000-000000000051',
     'query-v5-contract@example.test', repeat('a', 60),
     'Query V5 Contract', 'ACTIVE', now());

INSERT INTO neurosql_app.workspaces
    (workspace_id, name, slug, created_by_user_id)
VALUES
    ('00000000-0000-4000-8000-000000000052',
     'Query V5 Contract', 'query-v5-contract',
     '00000000-0000-4000-8000-000000000051');

INSERT INTO neurosql_app.workspace_memberships
    (workspace_id, user_id, role, status)
VALUES
    ('00000000-0000-4000-8000-000000000052',
     '00000000-0000-4000-8000-000000000051', 'OWNER', 'ACTIVE');

SET CONSTRAINTS ALL IMMEDIATE;
SET LOCAL ROLE neurosql_auth_runtime;

DO $$
DECLARE
    v_history_id UUID;
    v_saved_id UUID;
    v_count INTEGER;
BEGIN
    INSERT INTO neurosql_app.query_history
        (workspace_id, user_id, query_text, query_hash, outcome,
         row_count, execution_time_ms, truncated)
    VALUES
        ('00000000-0000-4000-8000-000000000052',
         '00000000-0000-4000-8000-000000000051',
         'SELECT 1', repeat('a', 64), 'SUCCEEDED', 1, 4, false)
    RETURNING history_id INTO v_history_id;

    SELECT count(*) INTO v_count
      FROM neurosql_app.query_history
     WHERE history_id = v_history_id
       AND workspace_id = '00000000-0000-4000-8000-000000000052'
       AND user_id = '00000000-0000-4000-8000-000000000051';

    IF v_count <> 1 THEN
        RAISE EXCEPTION 'Runtime role could not read workspace-scoped query history';
    END IF;

    BEGIN
        UPDATE neurosql_app.query_history
           SET query_text = 'SELECT 2'
         WHERE history_id = v_history_id;
        RAISE EXCEPTION 'Query history unexpectedly allowed UPDATE';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    BEGIN
        DELETE FROM neurosql_app.query_history WHERE history_id = v_history_id;
        RAISE EXCEPTION 'Query history unexpectedly allowed DELETE';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    INSERT INTO neurosql_app.saved_queries
        (workspace_id, owner_user_id, name, description, query_text)
    VALUES
        ('00000000-0000-4000-8000-000000000052',
         '00000000-0000-4000-8000-000000000051',
         'Daily report', 'A test query', 'SELECT 1')
    RETURNING saved_query_id INTO v_saved_id;

    UPDATE neurosql_app.saved_queries
       SET name = 'Daily report v2',
           description = 'Updated description',
           query_text = 'SELECT 2',
           updated_at = now()
     WHERE saved_query_id = v_saved_id;

    IF NOT EXISTS (
        SELECT 1 FROM neurosql_app.saved_queries
         WHERE saved_query_id = v_saved_id
           AND query_text = 'SELECT 2'
           AND name = 'Daily report v2'
    ) THEN
        RAISE EXCEPTION 'Saved-query update did not persist';
    END IF;

    BEGIN
        UPDATE neurosql_app.saved_queries
           SET owner_user_id = gen_random_uuid()
         WHERE saved_query_id = v_saved_id;
        RAISE EXCEPTION 'Saved query unexpectedly allowed owner mutation';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    BEGIN
        INSERT INTO neurosql_app.saved_queries
            (workspace_id, owner_user_id, name, query_text)
        VALUES
            ('00000000-0000-4000-8000-000000000052',
             '00000000-0000-4000-8000-000000000051',
             'daily REPORT V2', 'SELECT 3');
        RAISE EXCEPTION 'Case-insensitive duplicate saved-query name was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO neurosql_app.query_history
            (workspace_id, user_id, query_text, query_hash, outcome,
             row_count, execution_time_ms)
        VALUES
            (gen_random_uuid(),
             '00000000-0000-4000-8000-000000000051',
             'SELECT 9', repeat('b', 64), 'SUCCEEDED', 1, 1);
        RAISE EXCEPTION 'History row with a non-member workspace was accepted';
    EXCEPTION WHEN foreign_key_violation THEN
        NULL;
    END;

    DELETE FROM neurosql_app.saved_queries WHERE saved_query_id = v_saved_id;
    IF EXISTS (SELECT 1 FROM neurosql_app.saved_queries WHERE saved_query_id = v_saved_id) THEN
        RAISE EXCEPTION 'Saved-query DELETE did not work';
    END IF;
END
$$;

RESET ROLE;
ROLLBACK;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM neurosql_app.app_users
         WHERE email = 'query-v5-contract@example.test'
    ) OR EXISTS (
        SELECT 1 FROM neurosql_app.query_history
    ) OR EXISTS (
        SELECT 1 FROM neurosql_app.saved_queries
    ) THEN
        RAISE EXCEPTION 'V5 contract test left fixture rows behind';
    END IF;
END
$$;

SELECT 'V5 query-history/saved-query schema and least-privilege contract passed; fixtures rolled back.' AS result;
