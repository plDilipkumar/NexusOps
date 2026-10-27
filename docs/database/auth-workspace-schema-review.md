# Proposed durable authentication and workspace schema

**Status: draft only.** The SQL in `proposed/V1__durable_auth_workspaces.sql` is intentionally outside the application's migration-runner path. It has not been applied to the production `main` branch and must not be wired into application startup until the schema is approved.

## Infrastructure inspection (2026-10-27 20:31 UTC)

- GitHub source of truth: `plDilipkumar/NexusOps`, default branch `main`.
- Neon project: `neurosql`, PostgreSQL 17, Singapore region; database: `neurosql_db`.
- Read-only query against Neon `main` returned PostgreSQL 17.11 and zero visible application tables.
- Vercel project `neurosql` is linked to `plDilipkumar/NexusOps`; the latest production deployment reported `READY` for commit `6425ab427812864565edbbb77fb907ff65672145`.
- Render service `neurosql-api` uses the same GitHub repository, `main`, Docker context `backend`, Singapore region, and free plan. Auto-deploy is configured, though a previous merge required a manual deploy. The live deployment is commit `746d58da4d47b1fb49a6796dda8b424c8a6c969d`.

## Proposed tables

- `neurosql_app.app_users`: normalized email identity, password hash only, display name, account status and timestamps. A unique index on `lower(email)` prevents case-variant duplicate accounts.
- `neurosql_app.workspaces`: workspace name/slug and creator reference.
- `neurosql_app.workspace_memberships`: per-workspace roles (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`) and membership state. A partial unique index prevents multiple active owners; application logic must also prevent a workspace from ending up with zero active owners.
- `neurosql_app.user_sessions`: stores a SHA-256 hex digest of a high-entropy random session token, never the bearer token itself, with expiry and revocation timestamps.

The schema deliberately does not create a database login role or grant application privileges. Before enabling this feature, create/configure a separate least-privileged runtime role; the current Render connection uses the database owner and is not an acceptable isolation boundary for future SQL execution.

## Temporary-branch migration test

The exact proposed DDL was applied only to the temporary Neon branch `neurosql-auth-migration-check-20261028` (`br-shy-credit-b33ux9py`), cloned from `main`. It created four tables and ten indexes.

Verified on that temporary branch:

- Case-insensitive duplicate email rejected.
- Duplicate workspace slug rejected.
- A second active workspace owner rejected.
- Invalid membership role rejected.
- Invalid session-token hash rejected.
- Foreign-key relationships and valid session insertion succeeded.
- Temporary test rows were removed; all four application tables had zero rows after tests.
- `REVOKE` statements for the schema and its tables succeeded.
- The production `main` branch was checked separately and still has zero visible application tables.

These are structural migration checks, not a complete application authentication test. The migration is not production-ready until the session lifecycle, login rate limiting, password policy, email verification/reset flows, workspace authorization on every protected endpoint, and least-privileged database grants are implemented and tested.

## Approval boundary

Do not apply this migration to Neon `main`, add it to an automatically executed migration directory, or enable production authentication without explicit user approval. Keep production auth, schema browsing, and SQL validation disabled in the meantime.
