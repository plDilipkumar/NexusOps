-- PROPOSED ONLY: shared login-failure state for multi-instance durable authentication.
-- Apply only after V1__durable_auth_workspaces.sql and V2__durable_auth_runtime_grants.sql.
-- Never apply to Neon main or enable the JDBC limiter in production without explicit approval.
--
-- The application stores HMAC-SHA256 hex of a normalized login identity, never the raw
-- email. The HMAC key must be provided as a runtime secret (not committed to source).
-- The timestamp array preserves the current rolling 15-minute window exactly and is
-- capped at five failures. Row-level locking in the JDBC implementation serializes
-- concurrent updates across service instances.
--
-- Test this migration only on the temporary Neon branch and disposable PostgreSQL CI.

CREATE TABLE neurosql_app.auth_login_rate_limits (
    identity_hash VARCHAR(64) PRIMARY KEY
        CHECK (identity_hash ~ '^[0-9a-f]{64}$'),
    failure_timestamps TIMESTAMPTZ[] NOT NULL DEFAULT ARRAY[]::TIMESTAMPTZ[]
        CHECK (cardinality(failure_timestamps) <= 5)
        CHECK (array_position(failure_timestamps, NULL) IS NULL),
    blocked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT auth_login_rate_limit_block_consistency CHECK (
        (cardinality(failure_timestamps) < 5 AND blocked_until IS NULL)
        OR (cardinality(failure_timestamps) = 5 AND blocked_until IS NOT NULL)
    )
);

CREATE INDEX ix_auth_login_rate_limits_updated_at
    ON neurosql_app.auth_login_rate_limits (updated_at);

COMMENT ON TABLE neurosql_app.auth_login_rate_limits IS
    'Proposed shared rolling-window login limiter state. identity_hash is HMAC-SHA256 hex; no raw login identity is stored.';

REVOKE ALL ON TABLE neurosql_app.auth_login_rate_limits FROM PUBLIC;

GRANT SELECT (identity_hash, failure_timestamps, blocked_until, updated_at)
    ON neurosql_app.auth_login_rate_limits TO neurosql_auth_runtime;
GRANT INSERT (identity_hash, failure_timestamps, blocked_until, updated_at)
    ON neurosql_app.auth_login_rate_limits TO neurosql_auth_runtime;
GRANT UPDATE (failure_timestamps, blocked_until, updated_at)
    ON neurosql_app.auth_login_rate_limits TO neurosql_auth_runtime;
GRANT DELETE ON neurosql_app.auth_login_rate_limits TO neurosql_auth_runtime;
