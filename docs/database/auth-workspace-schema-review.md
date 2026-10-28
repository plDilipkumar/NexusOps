# Proposed durable authentication and workspace schema

**Status: draft only.** The SQL in `proposed/V1__durable_auth_workspaces.sql` and `proposed/V2__durable_auth_runtime_grants.sql` is intentionally outside the application's migration-runner path. Neither migration has been applied to Neon `main`; do not wire them into application startup until the schema is approved.

## Infrastructure inspection (2026-10-29 IST)

- GitHub source of truth: `plDilipkumar/NexusOps`, default branch `main`.
- Neon project: `neurosql`, PostgreSQL 17, Singapore region; database: `neurosql_db`.
- Read-only query against Neon `main` reports PostgreSQL 17.11, zero visible application tables and views. The existing `public.show_db_tree` helper is unrelated to the proposed auth schema.
- Temporary branch `neurosql-auth-owner-invariant-20261028` (`br-sweet-sky-b38kd7rm`) contains the four proposed `neurosql_app` tables and is used only for migration/privilege testing.
- Vercel project `neurosql` is linked to `plDilipkumar/NexusOps` on the Hobby plan; production alias `neurosql.vercel.app` remains configured.
- Render service `neurosql-api` uses the same repository, branch `main`, Docker context `backend`, Singapore region, and free plan. Auto-deploy is configured; the last verified production service commit remains `746d58d`.

## Proposed tables

- `neurosql_app.app_users`: normalized email identity, password hash only, display name, account status and timestamps. A unique index on `lower(email)` prevents case-variant duplicate accounts.
- `neurosql_app.workspaces`: workspace name/slug and creator reference.
- `neurosql_app.workspace_memberships`: per-workspace roles (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`) and membership state. A partial unique index prevents multiple active owners.
- `neurosql_app.user_sessions`: stores a SHA-256 hex digest of a high-entropy random session token, never the bearer token itself, with expiry and revocation timestamps.

## Workspace owner invariant

The proposed schema enforces **exactly one active owner at transaction end** using two deferred constraint triggers (one for workspace rows and one for membership changes), alongside the partial unique index that prevents multiple active owners. Workspace creation must insert the workspace and its initial owner membership in the same transaction. Owner transfer must suspend or demote the current owner and activate the replacement within one transaction; the deferred trigger checks the final transaction state. Deleting a workspace is allowed because the trigger skips checks when the workspace row no longer exists.

The schema contract test verifies that ownerless workspace creation and removal of the final active owner fail, while an atomic owner transfer succeeds.

## Proposed least-privilege runtime grants

`V2__durable_auth_runtime_grants.sql` creates a `NOLOGIN`, non-superuser group role named `neurosql_auth_runtime`. It grants only schema `USAGE`, column-scoped reads for account login and workspace authorization, column-scoped session insertion, and permission to update only `user_sessions.revoked_at`.

The runtime role intentionally cannot create objects, modify account/workspace rows, change a session token hash or user association, delete sessions, or read unrelated account columns such as `display_name` and `last_login_at`. The current repository queries were checked against the granted columns.

Production connection setup remains a separate, manual step: create a dedicated LOGIN role through Neon role management, grant it membership in `neurosql_auth_runtime`, and put its credential only in the Render environment. Do not use the database owner as the runtime identity. No production login role, Render environment variable, or secret was created or changed during this work.

## Temporary-branch migration and privilege tests

- The original schema version was tested on temporary Neon branch `neurosql-auth-migration-check-20261028` (`br-shy-credit-b33ux9py`).
- The updated schema with owner-invariant triggers was tested on temporary branch `neurosql-auth-owner-invariant-20261028` (`br-sweet-sky-b38kd7rm`), database `neurosql_db`, PostgreSQL 17.11.
- The updated branch contains four proposed tables, ten indexes, and both owner-invariant triggers.
- Direct temporary-branch tests passed for case-insensitive email uniqueness, duplicate workspace slug, duplicate active owner, invalid role, malformed session hash, foreign-key rejection, valid session insertion, atomic owner transfer, ownerless workspace rejection, and final-owner removal rejection.
- Applied the proposed V2 runtime role/grants on that temporary branch only. Read-only verification confirmed `rolcanlogin=false`, `rolsuper=false`, `rolcreatedb=false`, `rolcreaterole=false`, `rolreplication=false`, `rolbypassrls=false`, and `rolinherit=false`.
- A temporary transaction switched to `neurosql_auth_runtime` and successfully exercised the same account lookup, workspace membership lookup, session insert/read, and revocation operations required by the current JDBC repositories. Privilege checks confirmed no schema CREATE, no access to unrelated account/workspace columns, no ability to update `token_hash`, no session DELETE, and no membership INSERT. The transaction was rolled back; follow-up read-only checks found zero test rows. The temporary role/grants remain only on the isolated test branch.
- The GitHub Actions workflow now applies V1 and V2 only to disposable PostgreSQL 17, then runs schema and least-privilege contract scripts. CI verification for the new workflow is pending and will be recorded only after the run completes.
- Neon `main` remains unchanged with zero visible application tables/views.

These checks validate the proposed SQL and current repository access patterns; they are not production activation. The actual Neon temporary branch has not been used for HTTP login testing, and the runtime group role has not been granted to a production LOGIN role.

## Approval boundary

Do not apply either migration to Neon `main`, add them to an automatically executed migration directory, create production login credentials, or enable production durable authentication without explicit user approval. Keep production durable auth, schema browsing, and SQL validation disabled until the schema, role membership, and deployment configuration are approved and tested.
