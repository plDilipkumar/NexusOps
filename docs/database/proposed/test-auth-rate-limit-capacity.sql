-- Contract test for proposed V6 shared rate-limit capacity.
-- Runs only after V1-V6 on a disposable PostgreSQL database or temporary Neon branch.
-- Test rows are created and removed inside this script; never point it at Neon main.

DO $$
DECLARE
    five_failure_hash VARCHAR(64) := md5(gen_random_uuid()::text) || md5(gen_random_uuid()::text);
    ip_failure_hash VARCHAR(64) := md5(gen_random_uuid()::text) || md5(gen_random_uuid()::text);
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'neurosql_app.auth_login_rate_limits'::regclass
          AND conname = 'auth_login_rate_limits_failure_timestamps_check'
          AND pg_get_constraintdef(oid) LIKE '%30%'
    ) THEN
        RAISE EXCEPTION 'Rate-limit timestamp capacity was not expanded to thirty';
    END IF;

    -- A blocked five-failure login/email bucket remains valid.
    INSERT INTO neurosql_app.auth_login_rate_limits
        (identity_hash, failure_timestamps, blocked_until, updated_at)
    VALUES
        (five_failure_hash, array_fill(now(), ARRAY[5]), now() + interval '15 minutes', now());

    -- An unblocked IP bucket may accumulate failures up to twenty-nine.
    INSERT INTO neurosql_app.auth_login_rate_limits
        (identity_hash, failure_timestamps, blocked_until, updated_at)
    VALUES
        (ip_failure_hash, array_fill(now(), ARRAY[29]), NULL, now());

    -- The thirtieth failure is valid only when the IP bucket is blocked.
    UPDATE neurosql_app.auth_login_rate_limits
       SET failure_timestamps = array_fill(now(), ARRAY[30]),
           blocked_until = now() + interval '15 minutes',
           updated_at = now()
     WHERE identity_hash = ip_failure_hash;

    BEGIN
        UPDATE neurosql_app.auth_login_rate_limits
           SET failure_timestamps = array_fill(now(), ARRAY[31]),
               blocked_until = now() + interval '15 minutes',
               updated_at = now()
         WHERE identity_hash = ip_failure_hash;
        RAISE EXCEPTION 'A thirty-first failure unexpectedly passed the capacity constraint';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        UPDATE neurosql_app.auth_login_rate_limits
           SET failure_timestamps = array_fill(now(), ARRAY[4]),
               blocked_until = now() + interval '15 minutes',
               updated_at = now()
         WHERE identity_hash = five_failure_hash;
        RAISE EXCEPTION 'A blocked bucket with fewer than five failures unexpectedly passed';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    BEGIN
        UPDATE neurosql_app.auth_login_rate_limits
           SET failure_timestamps = array_fill(now(), ARRAY[30]),
               blocked_until = NULL,
               updated_at = now()
         WHERE identity_hash = ip_failure_hash;
        RAISE EXCEPTION 'An unblocked bucket at thirty failures unexpectedly passed';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;

    DELETE FROM neurosql_app.auth_login_rate_limits
     WHERE identity_hash IN (five_failure_hash, ip_failure_hash);
END
$$;

SELECT 'V6 rate-limit capacity contract passed; temporary fixtures removed.' AS result;
