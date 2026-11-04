-- CI contract test for proposed shared login rate-limit state.
-- Runs only against disposable PostgreSQL 17; never point this at Neon production.

DO $$
BEGIN
    IF NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.auth_login_rate_limits', 'identity_hash', 'SELECT')
       OR NOT has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.auth_login_rate_limits', 'failure_timestamps', 'UPDATE')
       OR NOT has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.auth_login_rate_limits', 'DELETE') THEN
        RAISE EXCEPTION 'Runtime role is missing required rate-limit state privileges';
    END IF;

    IF has_column_privilege(
            'neurosql_auth_runtime', 'neurosql_app.auth_login_rate_limits', 'identity_hash', 'UPDATE')
       OR has_table_privilege(
            'neurosql_auth_runtime', 'neurosql_app.auth_login_rate_limits', 'TRUNCATE')
       OR has_schema_privilege('neurosql_auth_runtime', 'neurosql_app', 'CREATE') THEN
        RAISE EXCEPTION 'Runtime role has excessive rate-limit privileges';
    END IF;
END
$$;

BEGIN;
SET LOCAL ROLE neurosql_auth_runtime;

INSERT INTO neurosql_app.auth_login_rate_limits
    (identity_hash, failure_timestamps, blocked_until, updated_at)
VALUES
    (repeat('b', 64), ARRAY[now()], NULL, now());

DO $$
DECLARE
    failure_count INTEGER;
BEGIN
    SELECT cardinality(failure_timestamps)
      INTO failure_count
      FROM neurosql_app.auth_login_rate_limits
     WHERE identity_hash = repeat('b', 64);

    IF failure_count <> 1 THEN
        RAISE EXCEPTION 'Runtime role could not read inserted rate-limit state';
    END IF;
END
$$;

UPDATE neurosql_app.auth_login_rate_limits
   SET failure_timestamps = ARRAY[
           now() - interval '4 minutes',
           now() - interval '3 minutes',
           now() - interval '2 minutes',
           now() - interval '1 minute',
           now()
       ],
       blocked_until = now() + interval '15 minutes',
       updated_at = now()
 WHERE identity_hash = repeat('b', 64);

DO $$
DECLARE
    failure_count INTEGER;
    current_block TIMESTAMPTZ;
BEGIN
    SELECT cardinality(failure_timestamps), blocked_until
      INTO failure_count, current_block
      FROM neurosql_app.auth_login_rate_limits
     WHERE identity_hash = repeat('b', 64);

    IF failure_count <> 5 OR current_block <= now() THEN
        RAISE EXCEPTION 'Rate-limit state did not preserve the five-failure block';
    END IF;
END
$$;

DELETE FROM neurosql_app.auth_login_rate_limits
 WHERE identity_hash = repeat('b', 64);

ROLLBACK;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM neurosql_app.auth_login_rate_limits
         WHERE identity_hash = repeat('b', 64)
    ) THEN
        RAISE EXCEPTION 'Rate-limit contract test left fixture rows behind';
    END IF;
END
$$;

SELECT 'Shared login rate-limit schema and grants contract passed; fixtures rolled back.' AS result;
