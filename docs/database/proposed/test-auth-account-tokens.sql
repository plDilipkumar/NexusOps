-- CI contract test for proposed email-verification/password-reset token storage.
-- Runs only on disposable PostgreSQL 17; never target Neon production.

DO $$
BEGIN
    IF (SELECT count(*) FROM information_schema.tables
        WHERE table_schema = 'neurosql_app' AND table_type = 'BASE TABLE') <> 6 THEN
        RAISE EXCEPTION 'Expected six proposed application tables including account_action_tokens';
    END IF;

    IF (SELECT count(*) FROM pg_indexes WHERE schemaname = 'neurosql_app') <> 16 THEN
        RAISE EXCEPTION 'Expected sixteen proposed indexes after V4';
    END IF;

    IF NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.app_users', 'email', 'INSERT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.app_users', 'password_hash', 'UPDATE')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.app_users', 'email_verified_at', 'UPDATE')
       OR has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.app_users', 'email_verified_at', 'INSERT') THEN
        RAISE EXCEPTION 'Account lifecycle grants are missing or too broad';
    END IF;

    IF NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.account_action_tokens', 'token_hash', 'SELECT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.account_action_tokens', 'consumed_at', 'UPDATE')
       OR has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.account_action_tokens', 'token_hash', 'UPDATE')
       OR has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.account_action_tokens', 'DELETE') THEN
        RAISE EXCEPTION 'Account action token privileges are missing or excessive';
    END IF;
END
$$;

BEGIN;
SET LOCAL ROLE neurosql_auth_runtime;

DO $$
DECLARE
    v_user_id UUID;
    v_token_id UUID;
    v_token_hash VARCHAR(64) := repeat('c', 64);
BEGIN
    INSERT INTO neurosql_app.app_users (email, password_hash, display_name)
    VALUES ('token-contract@example.test', repeat('a', 60), 'Token Contract')
    RETURNING user_id INTO v_user_id;

    INSERT INTO neurosql_app.account_action_tokens
        (user_id, purpose, token_hash, expires_at)
    VALUES (v_user_id, 'EMAIL_VERIFICATION', v_token_hash, now() + interval '1 hour')
    RETURNING token_id INTO v_token_id;

    UPDATE neurosql_app.app_users
       SET email_verified_at = now(), updated_at = now()
     WHERE user_id = v_user_id;

    UPDATE neurosql_app.account_action_tokens
       SET consumed_at = now()
     WHERE token_id = v_token_id;

    IF NOT EXISTS (
        SELECT 1 FROM neurosql_app.account_action_tokens
         WHERE token_id = v_token_id AND consumed_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Runtime role could not consume a token';
    END IF;

    BEGIN
        UPDATE neurosql_app.account_action_tokens
           SET consumed_at = NULL
         WHERE token_id = v_token_id;
        RAISE EXCEPTION 'Expected consumed token reactivation to be rejected';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        UPDATE neurosql_app.account_action_tokens
           SET token_hash = repeat('d', 64)
         WHERE token_id = v_token_id;
        RAISE EXCEPTION 'Expected token hash mutation to be denied';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    BEGIN
        DELETE FROM neurosql_app.account_action_tokens WHERE token_id = v_token_id;
        RAISE EXCEPTION 'Expected direct token deletion to be denied';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;

    BEGIN
        INSERT INTO neurosql_app.account_action_tokens
            (user_id, purpose, token_hash, expires_at)
        VALUES (v_user_id, 'UNKNOWN', repeat('e', 64), now() + interval '1 hour');
        RAISE EXCEPTION 'Expected unsupported token purpose to be rejected';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        INSERT INTO neurosql_app.account_action_tokens
            (user_id, purpose, token_hash, expires_at)
        VALUES (v_user_id, 'PASSWORD_RESET', repeat('f', 64), now() - interval '1 second');
        RAISE EXCEPTION 'Expected already-expired token to be rejected';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END
$$;

ROLLBACK;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM neurosql_app.account_action_tokens)
       OR EXISTS (SELECT 1 FROM neurosql_app.app_users) THEN
        RAISE EXCEPTION 'Account-token contract test left fixture rows behind';
    END IF;
END
$$;

SELECT 'Account action token schema, one-time consumption, and least-privilege contract passed.' AS result;
