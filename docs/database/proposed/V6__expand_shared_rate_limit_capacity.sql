-- PROPOSED ONLY: expand shared rate-limit capacity for trusted client-IP buckets.
-- Depends on V3__shared_login_rate_limits.sql.
-- Keep login and email identities at five attempts; account lifecycle IP buckets
-- may retain up to thirty failures in the same rolling 15-minute window.
-- Apply with psql --single-transaction, only on a temporary Neon review branch until the user approves the schema.
-- Never apply this migration to Neon main autonomously.

ALTER TABLE neurosql_app.auth_login_rate_limits
    DROP CONSTRAINT auth_login_rate_limits_failure_timestamps_check;

ALTER TABLE neurosql_app.auth_login_rate_limits
    ADD CONSTRAINT auth_login_rate_limits_failure_timestamps_check
        CHECK (cardinality(failure_timestamps) <= 30);

ALTER TABLE neurosql_app.auth_login_rate_limits
    DROP CONSTRAINT auth_login_rate_limit_block_consistency;

ALTER TABLE neurosql_app.auth_login_rate_limits
    ADD CONSTRAINT auth_login_rate_limit_block_consistency CHECK (
        (blocked_until IS NULL AND cardinality(failure_timestamps) < 30)
        OR (
            blocked_until IS NOT NULL
            AND cardinality(failure_timestamps) BETWEEN 5 AND 30
        )
    );

COMMENT ON CONSTRAINT auth_login_rate_limit_block_consistency
    ON neurosql_app.auth_login_rate_limits IS
    'Allows five-failure login/email buckets and thirty-failure trusted client-IP buckets; blocked state must match a threshold-sized rolling window.';
