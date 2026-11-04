-- PROPOSED ONLY: allow the trusted auth runtime to bootstrap one personal workspace.
-- Apply after V1-V7 on a temporary branch first. Never apply to Neon main without
-- explicit user approval.
--
-- RegistrationService creates the workspace and its OWNER membership in the same
-- transaction as the unverified account and verification token. The runtime may
-- INSERT only the required columns; it cannot change ownership or membership later.

GRANT INSERT (name, slug, created_by_user_id)
    ON neurosql_app.workspaces TO neurosql_auth_runtime;

GRANT INSERT (workspace_id, user_id, role, status)
    ON neurosql_app.workspace_memberships TO neurosql_auth_runtime;
