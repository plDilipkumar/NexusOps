# Proposed durable authentication and workspace schema

**Status: V1–V6 approved and applied to Neon `main` on 2026-11-04 IST; V7 remains proposal-only for production.** The SQL in `proposed/V1__durable_auth_workspaces.sql` and `proposed/V2__durable_auth_runtime_grants.sql` is intentionally outside the application's migration-runner path. Neither migration has been applied to Neon `main`; do not wire them into application startup until the schema is approved.

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

## Proposed isolated read-only SQL execution role (V7)

The proposed V7 migration creates a separate `NOLOGIN`, `NOINHERIT`, non-superuser group role named `neurosql_sql_readonly`. It grants only database CONNECT and explicitly revokes access to the protected `neurosql_app` authentication schema, including its tables, sequences, and functions.

The role intentionally receives **no blanket SELECT grant**. An operator must grant USAGE on a dedicated data schema and SELECT on only the approved tables. Do not grant it access to `neurosql_app`, and do not use broad `GRANT SELECT ON ALL TABLES IN SCHEMA public` unless that schema is deliberately dedicated to user-queryable data. A separate LOGIN role must be provisioned outside source control, granted membership in this group, and configured only through the Render SQL data-plane environment variables.

The application SQL executor already requires membership in `neurosql_sql_readonly`, verifies the login is not the database owner and has no unexpected role memberships, rejects schema/database creation and table/sequence write privileges, and enforces a PostgreSQL read-only transaction plus statement timeout. V7 supplies the missing NOLOGIN group role; it does not create production login credentials or enable SQL execution.

The new CI contract test creates a disposable LOGIN member and probe schema inside a transaction, proves an explicitly granted SELECT works while INSERT/UPDATE/DELETE/TRUNCATE/CREATE fail, then rolls back the temporary role and schema. GitHub Actions run #451 passed this V7 migration and contract test on disposable PostgreSQL 17. The same group role was applied only to Neon review branch `br-silent-cherry-b3k2vqtj`; role attributes, database CONNECT, absence of authentication-schema access, and explicit SELECT-only access on a branch-local probe table were verified.

## Shared account-request rate-limit capacity (proposed V6)

The proposed V6 migration expands the existing auth_login_rate_limits.failure_timestamps array cap from five to thirty and relaxes the block-consistency check so the same HMAC-keyed table can safely hold:
- Five-attempt login/email buckets.
- Thirty-attempt registration/resend client-IP buckets.
- Thirty-attempt email-verification-consumption client-IP buckets.

The Java limiter supplies a per-bucket threshold; the SQL row lock serializes updates across instances. Raw email, IP address, and token values are not persisted. Registration and verification-resend email identities use separate HMAC namespaces, while their trusted client-IP bucket is shared. IP quotas are omitted unless an operator-configured trusted-proxy resolver provides a client address.

V6 does not add a table or alter account/session data. It only replaces two CHECK constraints on the proposed rate-limit table. The migration and its contract test are included in PR #7 and run against disposable PostgreSQL 17 in CI; the CI applies V6 with psql --single-transaction so the constraint replacement is atomic. The migration has not been applied to Neon main.
## Approval boundary

The user explicitly approved PR #7 and V1–V6 for production on 2026-11-04 IST. The approved V1–V6 bundle was applied to Neon project `neurosql`, production branch `main`, database `neurosql_db`, in one transaction containing 66 SQL statements.

Production verification on PostgreSQL 17.11 confirmed:
- Eight application tables and two trigger functions exist under `neurosql_app`.
- The `neurosql_auth_runtime` group role exists with NOLOGIN, NOINHERIT, NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOREPLICATION, and NOBYPASSRLS.
- The role has schema USAGE but no CREATE; it can read only the approved account/session/workspace columns, insert/revoke sessions, maintain rate-limit state, create/read action tokens, append query history, and manage saved queries within the granted columns.
- It cannot read `app_users.display_name`, change `user_sessions.token_hash`, delete sessions, or access the schema through PUBLIC.
- All eight application tables contain zero rows. The production schema comparison against independent temporary branch `br-aged-violet-b3ma06dw` returned an empty diff.

Production activation is still deliberately incomplete:
- No LOGIN principal has been created or granted membership in `neurosql_auth_runtime`; the current Render datasource has not been switched away from its existing identity.
- V7 and the `neurosql_sql_readonly` group role remain proposal-only; no SQL-execution LOGIN exists.
- Render auth, schema browsing, SQL validation/execution, query history, saved queries, and AI flags are explicitly false. A 256-bit HMAC key was generated and stored as a Render environment secret; its value is not recorded here.
- SMTP credentials were not available and were not invented. The presence of the Gemini key could not be independently read through the available Render integration, and no live Gemini request was made. AI remains disabled.

Do not create a production LOGIN role, apply V7, enable authentication/SQL/AI, or replace datasource credentials until the remaining role/credential configuration has been separately approved and verified. Keep all secrets in Render only.
## V6 temporary-branch validation (2026-11-03 IST)

- Created temporary branch `neurosql-rate-limit-v6-review-20261103` (`br-silent-cherry-b3k2vqtj`) from the V5 review branch `br-morning-scene-b35jbi7a`; database `neurosql_db`, PostgreSQL 17.11.
- Confirmed the parent review branch rate-limit table contained zero rows before branching.
- Applied only the proposed V6 constraint changes on the new temporary branch. The 64-character HMAC identity constraint, NULL-array-element check, primary key, and updated-at index were preserved.
- Verified that five-failure blocked login/email rows and 29-failure unblocked IP rows are accepted; thirty-failure blocked IP rows are accepted; thirty-first failures, blocked rows below five, and unblocked rows at thirty are rejected.
- The temporary-branch contract test passed and left zero rate-limit rows behind.
- GitHub Actions run #345 passed backend tests, frontend build, and the disposable PostgreSQL 17 schema-contract job, including V6 application and its 30-attempt contract script.
- Neon production `main` remains unchanged: `neurosql_db` still has zero application tables and only the `neurosql_owner` LOGIN role. V6 has not been applied to `main`.

## V7 temporary-branch validation (2026-11-04 IST)

- Applied the V7 `neurosql_sql_readonly` NOLOGIN group-role definition only to Neon review branch `neurosql-rate-limit-v6-review-20261103` (`br-silent-cherry-b3k2vqtj`), database `neurosql_db`, PostgreSQL 17.11. Neon `main` was not changed.
- Verified `rolcanlogin=false`, `rolinherit=false`, and all of `rolsuper`, `rolcreatedb`, `rolcreaterole`, `rolreplication`, and `rolbypassrls` are false. The role can CONNECT but cannot CREATE in the database.
- Verified the group has no USAGE/CREATE on `neurosql_app` and cannot SELECT `app_users` or `user_sessions`.
- Created a branch-only probe schema/table, granted only schema USAGE and table SELECT, and verified SELECT=true while INSERT/UPDATE/DELETE/TRUNCATE/CREATE=false. The probe is a test fixture on the review branch, not part of the proposed migration.
- GitHub Actions run #451 passed V1-V7 application and all schema/role contract scripts on disposable PostgreSQL 17, including the V7 rollback test for a temporary LOGIN member and probe schema.
- A production LOGIN role, password, Render SQL data-plane credentials, and any access to Neon `main` remain unconfigured pending explicit approval and secret handling.
