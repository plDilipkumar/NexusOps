# NeuroSQL Progress

Last verified: 2026-10-29 10:04 IST

## Verified — main branch and production baseline
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Vercel project `neurosql` is linked to `plDilipkumar/NexusOps` and uses the Hobby/free plan. The custom domain is `neurosql.vercel.app`; latest inspected draft preview reports `READY`.
- [x] Neon project `neurosql` is in Singapore on PostgreSQL 17; default branch `main` is ready.
- [x] Read-only query on Neon `main`, database `neurosql_db`, confirmed PostgreSQL 17.11, zero visible application tables, and zero visible application views. No production schema changes were made.
- [x] React + TypeScript + Vite frontend and Spring Boot 3 / Java 21 backend are present.
- [x] Render `neurosql-api` is on the free plan in the confirmed workspace, Singapore region, repository `plDilipkumar/NexusOps`, branch `main`, Docker context `backend`; auto-deploy is configured.
- [x] Last verified Render production deployment `dep-dbgfqe6gekts738l9eng` is live for commit `746d58d`. The prior production smoke test in GitHub Actions run #31 confirmed API status `UP`, database `neurosql_db`, PostgreSQL `17.11`.
- [x] Render environment variables point to the dedicated NeuroSQL Neon database; credentials are not stored in GitHub.
- [x] Frontend polls database health on load and every 60 seconds, with connected/unavailable states.
- [x] Read-only `GET /api/schema` reads PostgreSQL information-schema metadata and groups schemas, tables, and ordered columns. It remains disabled by default.
- [x] PR #4 merged as commit `69d7b53`: Spring Security, environment-configured single-owner HTTP Basic authentication, BCrypt password hashing in memory, fail-closed authorization for `/api/schema`, public health routes, and restricted CORS.
- [x] Security tests cover unauthenticated schema access, configured-owner access, public health, CORS preflight, and denial when auth is disabled. GitHub Actions run #19 passed backend verification and frontend build.
- [x] PR #5 merged as commit `deeb794`: gated schema explorer UI, owner Basic-auth sign-in, schema/table/column rendering, expandable columns, refresh and sign-out. Credentials stay in React memory, not browser storage.
- [x] Added `frontend/.env.example` and documented coordinated frontend/backend flags. GitHub Actions run #24 passed backend verification and frontend build.
- [x] Production authentication, schema browser, and SQL validator remain disabled. No secrets were committed and no application tables were created on Neon `main`.

## SQL validation guardrail — merged in PR #6
- [x] Added disabled-by-default `POST /api/sql/validate` behind the owner authentication boundary. It analyzes drafts only and never executes SQL.
- [x] Validator conservatively rejects multiple statements, common mutation keywords, `SELECT INTO`, selected side-effecting PostgreSQL functions, and row-locking clauses; it warns that this lexical heuristic is not a PostgreSQL parser or proof of safety.
- [x] CORS POST permission is scoped to `/api/sql/**`; unrelated API routes remain GET/OPTIONS only.
- [x] Tests cover SELECT/CTE behavior, mutation and multi-statement rejection, quoted/commented keywords, escape-string boundaries, side-effecting functions, authentication, fail-closed behavior, request validation, and POST CORS preflight.
- [x] PR #6 merged as commit `746d58d`. GitHub Actions run #31 passed backend verification, frontend build, and production API/database smoke test.
- [x] Render reports the merged backend deployment live. SQL validation remains disabled in production; no SQL is executed.

## Durable auth/workspace schema proposal — draft only; not approved for production
- [x] Inspected GitHub, Neon, Vercel, Render service/deploy history, and current security code before choosing this milestone.
- [x] Original proposed schema was applied only to temporary Neon branch `neurosql-auth-migration-check-20261028` (`br-shy-credit-b33ux9py`); production `main` remained unchanged.
- [x] Added proposed DDL under `docs/database/proposed/`, outside the application's migration-runner path. Draft PR #7 remains open and unmerged.
- [x] Added repeatable CI contract tests and an ephemeral PostgreSQL 17 GitHub Actions job. The workflow never connects to Neon.
- [x] Workflow optimization commit `00689646f730dff1e9c7ba661851f7597a1f1936` was verified: GitHub Actions run #38 completed successfully, and the Vercel preview reported `READY`.
- [x] Improved the draft schema to enforce exactly one active workspace owner at transaction end with two deferred constraint triggers, in addition to the unique index that prevents multiple active owners. Workspace creation and owner transfer must be atomic transactions.
- [x] Applied the updated proposed DDL only to fresh temporary Neon branch `neurosql-auth-owner-invariant-20261028` (`br-sweet-sky-b38kd7rm`), database `neurosql_db`, PostgreSQL 17.11.
- [x] Verified updated branch contains four tables, ten indexes, and two owner-invariant triggers. Direct tests passed for duplicate email/slug/owner rejection, invalid role and token hash, foreign-key rejection, valid session insertion, atomic owner transfer, ownerless workspace rejection, and final-owner removal rejection.
- [x] Direct-test fixtures were rolled back; read-only query found zero rows in all four proposed tables.
- [x] GitHub Actions run #42 passed backend Maven verification, frontend production build, and the updated schema contract tests on disposable PostgreSQL 17. The production smoke job was skipped for the draft PR as expected.
- [x] Vercel preview for draft commit `9838791326fb6d7c659e0129cd68b1bc46eba0a5` reports `READY`.
- [x] Separate read-only query confirmed Neon `main` still has zero visible application tables and views.
- [ ] Durable authentication remains unimplemented: session lifecycle, login rate limiting, password policy, email verification/reset, authorization on every protected endpoint, and a least-privileged runtime database role are still required.

## In progress / blockers
- [ ] Review and approve or revise draft PR #7 before any production schema change. Do not merge the migration or apply it to Neon `main` without explicit approval.
- [ ] Verify browser-side CORS and rendered sign-in/metadata behavior only after auth credentials and feature flags are deliberately configured; production auth and schema browsing remain disabled.
- [ ] Investigate Render auto-deploy reliability. The prior PR #6 merge required manual deployment despite auto-deploy being enabled. No new backend deployment was needed for the current draft-only schema/CI changes.
- [ ] The production health endpoint could not be fetched by the available web tool in this run; the last verified live API/database smoke test remains GitHub Actions run #31.

## Next milestones
- [ ] After schema approval, add a controlled migration runner and database-backed authentication behind disabled feature flags; test on a temporary Neon branch before any production change.
- [ ] Implement secure session issuance, token hashing, expiry/revocation, login rate limiting, password policy, and workspace membership authorization for every protected endpoint.
- [ ] Configure a least-privileged database runtime role and explicit grants before enabling database-backed auth or SQL features.
- [ ] Add safe read-only SQL execution only after access control and database-side read-only transaction enforcement are in place; keep query history disabled until its persistence and authorization design is reviewed.
- [ ] Add EXPLAIN performance analysis.
- [ ] Prepare any future schema migrations on a temporary Neon branch and stop for approval before applying them to Neon `main`.

## Constraints
- Keep within free-tier usage; no paid resources.
- Never reuse another project's database.
- Never run destructive SQL automatically.
- Do not claim a feature or deployment is complete until verified.
- Read this file before each work session and avoid repeating completed work.


## Proposed shared login rate-limit schema — draft PR #7
- [x] Re-read the current progress tracker and inspected the durable-login rate limiter, login service, proposed V1/V2 schema, Neon branches/databases, Vercel linkage/deployments, and Render service/deploy history before selecting this step.
- [x] Added proposed-only `docs/database/proposed/V3__shared_login_rate_limits.sql`. The table stores an HMAC-SHA256 hex identity key (not the raw email), a bounded rolling array of up to five failure timestamps, the block expiry, and an update timestamp; it includes consistency checks and an update-time index.
- [x] Granted `neurosql_auth_runtime` only the required read/insert columns, updates to failure timestamps/block expiry/update time, and DELETE for later stale-row cleanup. The role cannot update the identity hash or TRUNCATE the table.
- [x] Added `test-auth-login-rate-limits.sql` and updated the schema-contract CI job to apply V3 after V1/V2 and test privileges, the five-failure block representation, and fixture rollback on disposable PostgreSQL 17.
- [x] Initial GitHub Actions run #149 (`40781167176`) failed because the existing schema-contract test hard-coded four tables and ten indexes. Updated its expectations to five tables and twelve indexes; run #150 (`40781414857`) then passed backend Maven verification, frontend production build, schema/constraint tests, runtime-grant tests, and shared-rate-limit tests. Production smoke was skipped as expected for the unmerged draft branch.
- [x] Applied the additive V3 migration only to temporary Neon branch `neurosql-auth-owner-invariant-20261028` (`br-sweet-sky-b38kd7rm`), database `neurosql_db`. Read-only checks confirmed five application tables, twelve indexes, the intended constraints and grants, no UPDATE privilege on `identity_hash`, no TRUNCATE privilege, and zero leftover test rows.
- [x] Rechecked Neon `main` (`br-empty-credit-b360dmpc`): `neurosql_db` is PostgreSQL 17.11 and still has zero visible application tables/views; its role list still contains only `neurosql_owner`. No production SQL writes or migrations were performed.
- [x] Vercel project `neurosql` remains associated with `plDilipkumar/NexusOps`; the latest preview for commit `71e78e1176e9519efca3a7d07b7f736ceaa6ab92` reports `READY`. Render `neurosql-api` remains free-tier, linked to repository branch `main`, Docker context `backend`; the live deployment remains commit `746d58d`. No backend deployment or environment change was triggered.
- [ ] This is the shared-state schema proposal and its contract test only; the JDBC limiter is not yet implemented or wired. Next, add a validated HMAC key configuration, transactional row-locking operations and bounded stale-row cleanup, then test concurrent requests against disposable PostgreSQL. Keep the legacy Basic-auth limiter separate.
- [ ] Draft PR #7 remains unapproved/unmerged. Do not apply V3 or any other proposed auth migration to Neon `main` without explicit user approval. Durable auth remains disabled in production.
- [x] No credentials were committed, no paid resources were introduced, and no production environment settings or schema were changed.


## Proposed account verification and password-reset token schema — draft PR #7 (2026-10-29)
- [x] Added proposed-only `docs/database/proposed/V4__account_action_tokens.sql` with `account_action_tokens`, a purpose allowlist (`EMAIL_VERIFICATION`, `PASSWORD_RESET`), SHA-256-hex token digests only, expiry/consumption constraints, a single-use trigger that prevents resetting `consumed_at`, and indexes for user/purpose history and expired-token maintenance.
- [x] Added least-privilege grants for the runtime group: it can create an unverified account without setting `email_verified_at`, update only password/verification/timestamp fields, read/insert action-token fields, and update only `consumed_at`. It cannot update token hashes or directly delete action tokens.
- [x] Added `test-auth-account-tokens.sql` and wired V4 plus its contract test into the schema-contract CI job. Updated the schema contract to expect six application tables and sixteen indexes after V4.
- [x] GitHub Actions run #175 (https://github.com/plDilipkumar/NexusOps/actions/runs/40818857840) passed backend Maven verification, frontend production build, and all proposed-schema contract steps on disposable PostgreSQL 17, including V4 privilege, constraint, single-use, and fixture-rollback checks. Production smoke was skipped for the unmerged draft PR.
- [x] Applied the additive V4 proposal only to temporary Neon branch `br-sweet-sky-b38kd7rm`, database `neurosql_db`. Read-only checks confirmed six application tables, sixteen indexes, one single-use trigger, expected runtime grants, and zero remaining users/action-token/rate-limit test rows. A direct temporary-branch transaction exercised token consumption and rejection of reactivation, unsupported purpose, and expired tokens.
- [x] Rechecked Neon `main` (`br-empty-credit-b360dmpc`): `neurosql_db` remains PostgreSQL 17.11 with zero visible application tables. No production SQL writes or migrations were performed.
- [x] Rechecked Vercel project `neurosql` linkage to `plDilipkumar/NexusOps` and Render service `neurosql-api`: Render remains on the free plan, linked to branch `main`, with live deployment commit `746d58d`. No backend deployment or environment change was triggered.
- [ ] Vercel reports a build-rate-limit failure for the SQL/CI-only commit `789f3ec`; no successful preview for this schema commit is claimed. The latest previously ready feature preview is not a production release.
- [ ] PR #7 remains draft/unapproved/unmerged. Do not apply V1–V4 to Neon `main` without explicit user approval. Durable auth is still disabled in production; no production LOGIN role or HMAC key has been configured.
- [ ] V4 is schema groundwork only: registration, email delivery, verification/reset token issuance and redemption endpoints, token cleanup policy, and workspace lifecycle administration remain unfinished.
- [x] No credentials were committed, no paid resources were introduced, and no production environment settings, data, schema, or backend deployment were changed.
