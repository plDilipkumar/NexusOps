# Proposed durable authentication and workspace schema

**Status: draft only.** The SQL in `proposed/V1__durable_auth_workspaces.sql` is intentionally outside the application's migration-runner path. It has not been applied to the production `main` branch and must not be wired into application startup until the schema is approved.

## Infrastructure inspection (2026-10-28 04:03 UTC)

- GitHub source of truth: `plDilipkumar/NexusOps`, default branch `main`.
- Neon project: `neurosql`, PostgreSQL 17, Singapore region; database: `neurosql_db`.
- Read-only query against Neon `main` returned PostgreSQL 17.11, zero visible application tables, and zero visible application views.
- Vercel project `neurosql` is linked to `plDilipkumar/NexusOps`; latest inspected deployment for the draft branch reports `READY`.
- Render service `neurosql-api` uses the same repository, `main`, Docker context `backend`, Singapore region, and free plan. Auto-deploy is configured; a prior merge required a manual deployment. The last verified production service commit remains `746d58d`.

## Proposed tables

- `neurosql_app.app_users`: normalized email identity, password hash only, display name, account status and timestamps. A unique index on `lower(email)` prevents case-variant duplicate accounts.
- `neurosql_app.workspaces`: workspace name/slug and creator reference.
- `neurosql_app.workspace_memberships`: per-workspace roles (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`) and membership state. A partial unique index prevents multiple active owners.
- `neurosql_app.user_sessions`: stores a SHA-256 hex digest of a high-entropy random session token, never the bearer token itself, with expiry and revocation timestamps.

## Workspace owner invariant

The proposed schema now enforces **exactly one active owner at transaction end** using two deferred constraint triggers (one for workspace rows and one for membership changes), in addition to the partial unique index that prevents multiple active owners. Workspace creation must insert the workspace and its initial owner membership in the same transaction. Owner transfer must suspend or demote the current owner and activate the replacement within one transaction; the deferred trigger evaluates the final transaction state. Deleting a workspace is allowed because the trigger skips checks when the workspace row no longer exists.

The contract test explicitly checks that ownerless workspace creation and removal of the last active owner fail, while an atomic owner transfer succeeds.

## Database privileges and remaining readiness requirements

The schema revokes public privileges on the application schema, tables, and functions. It deliberately does not create a database login role or grant application privileges. Before enabling this feature, configure a separate least-privileged runtime role and explicit grants; the current Render connection uses the database owner and is not an acceptable isolation boundary for future SQL execution.

## Temporary-branch migration test

The original schema was tested on Neon temporary branch `neurosql-auth-migration-check-20261028` (`br-shy-credit-b33ux9py`), cloned from `main`, before the deferred owner-invariant trigger was added. The updated DDL must be tested separately on a fresh temporary Neon branch before the proposal is considered validated.

These are structural migration checks, not a complete application authentication test. Session lifecycle, login rate limiting, password policy, email verification/reset flows, workspace authorization on every protected endpoint, and least-privileged database grants remain unimplemented.

## Approval boundary

Do not apply this migration to Neon `main`, add it to an automatically executed migration directory, or enable production authentication without explicit user approval. Keep production auth, schema browsing, and SQL validation disabled in the meantime.
