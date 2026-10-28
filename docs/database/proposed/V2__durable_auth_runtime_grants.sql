-- PROPOSED ONLY: least-privilege grants for durable authentication.
-- Apply only after V1__durable_auth_workspaces.sql on a temporary Neon branch.
-- Never apply to Neon main or configure production login roles without explicit approval.
--
-- This creates a NOLOGIN group role. In production, provision a separate LOGIN
-- principal outside source control (Neon role management), grant this group role
-- to it, and store only the login credential in Render environment variables.
-- Do not use the database owner as the application runtime identity.

CREATE ROLE neurosql_auth_runtime
    NOLOGIN
    NOINHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS;

GRANT USAGE ON SCHEMA neurosql_app TO neurosql_auth_runtime;

-- Login needs to locate an account and verify its BCrypt hash, but it does not
-- need display names, login history, or account timestamps.
GRANT SELECT (user_id, email, password_hash, status, email_verified_at)
    ON neurosql_app.app_users TO neurosql_auth_runtime;

-- Workspace discovery/authorization is read-only and limited to the fields
-- returned by the current repository queries.
GRANT SELECT (workspace_id, name, slug)
    ON neurosql_app.workspaces TO neurosql_auth_runtime;
GRANT SELECT (workspace_id, user_id, role, status)
    ON neurosql_app.workspace_memberships TO neurosql_auth_runtime;

-- Session lifecycle needs to create sessions, read/validate them, and set
-- revoked_at. It must not delete sessions or alter token hashes/user ownership.
GRANT SELECT (session_id, user_id, token_hash, created_at, expires_at, revoked_at)
    ON neurosql_app.user_sessions TO neurosql_auth_runtime;
GRANT INSERT (user_id, token_hash, created_at, expires_at)
    ON neurosql_app.user_sessions TO neurosql_auth_runtime;
GRANT UPDATE (revoked_at)
    ON neurosql_app.user_sessions TO neurosql_auth_runtime;
