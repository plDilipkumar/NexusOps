-- PROPOSED ONLY: durable user/workspace authentication schema.
-- This file is intentionally stored under docs/database/proposed so it is not
-- picked up by an application migration runner. Do not apply to the main Neon
-- branch or wire into startup migrations without explicit approval.
--
-- Tested on temporary Neon branch:
--   neurosql-auth-migration-check-20261028
--   database neurosql_db, PostgreSQL 17.11

CREATE SCHEMA neurosql_app;

CREATE TABLE neurosql_app.app_users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL CHECK (email <> '' AND email = btrim(email)),
    password_hash VARCHAR(255) NOT NULL CHECK (length(password_hash) >= 50),
    display_name VARCHAR(120) NOT NULL CHECK (display_name <> '' AND display_name = btrim(display_name)),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    email_verified_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Store normalized email in application code and enforce case-insensitive uniqueness in PostgreSQL.
CREATE UNIQUE INDEX uq_app_users_email_lower
    ON neurosql_app.app_users (lower(email));

CREATE TABLE neurosql_app.workspaces (
    workspace_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL CHECK (name <> '' AND name = btrim(name)),
    slug VARCHAR(80) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    created_by_user_id UUID NOT NULL
        REFERENCES neurosql_app.app_users(user_id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_workspaces_slug_lower
    ON neurosql_app.workspaces (lower(slug));

CREATE TABLE neurosql_app.workspace_memberships (
    workspace_id UUID NOT NULL
        REFERENCES neurosql_app.workspaces(workspace_id) ON DELETE CASCADE,
    user_id UUID NOT NULL
        REFERENCES neurosql_app.app_users(user_id) ON DELETE RESTRICT,
    role VARCHAR(16) NOT NULL
        CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INVITED', 'SUSPENDED')),
    invited_by_user_id UUID
        REFERENCES neurosql_app.app_users(user_id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id),
    CHECK (invited_by_user_id IS NULL OR invited_by_user_id <> user_id)
);

-- A workspace may have at most one active owner. Service logic must also prevent zero active owners.
CREATE UNIQUE INDEX uq_workspace_active_owner
    ON neurosql_app.workspace_memberships (workspace_id)
    WHERE role = 'OWNER' AND status = 'ACTIVE';

CREATE INDEX ix_workspace_memberships_user_status
    ON neurosql_app.workspace_memberships (user_id, status);

CREATE TABLE neurosql_app.user_sessions (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL
        REFERENCES neurosql_app.app_users(user_id) ON DELETE CASCADE,
    -- Store SHA-256 hex of a high-entropy random bearer token, never the raw token.
    token_hash VARCHAR(64) NOT NULL UNIQUE
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX ix_user_sessions_active_user_expiry
    ON neurosql_app.user_sessions (user_id, expires_at)
    WHERE revoked_at IS NULL;

-- No application role is granted access here. A separate least-privileged runtime
-- role and explicit grants must be designed before enabling database-backed auth.
REVOKE ALL ON SCHEMA neurosql_app FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA neurosql_app FROM PUBLIC;
