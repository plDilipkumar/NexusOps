# NeuroSQL — release progress

Last verified: 2026-11-04 (UTC timestamps are recorded below). The detailed earlier run notes remain in Git history; this file is kept concise to avoid stale duplicated status sections.

## Current release state

- Repository: [plDilipkumar/NexusOps](https://github.com/plDilipkumar/NexusOps)
- Current application commit: `187f4802bb6c0979d99904860e1407d22d80fcf0` — PR #12, personal workspace bootstrap.
- Open PRs: 0 after PR #12 merged.
- Main CI: run #483 (`41817996488`), attempt 2, all five jobs passed. PR CI run #482 (`41817568439`) passed all four PR jobs.
- Current frontend deployment: Vercel `dpl_B8mSjJh4Uuy5aizXHxPKz7Dfmg5U`, READY, aliases include `neurosql.vercel.app`.
- Current backend deployment: Render `dep-dblh208u01pc73a6onbg`, LIVE, for application commit `187f4802`.
- Production database: Neon project `neurosql` (`lingering-dew-88806235`), main branch `br-empty-credit-b360dmpc`, database `neurosql_db`, PostgreSQL 17.11, Free tier.

## Latest verified milestone — personal workspace bootstrap

PR #12, [Create a personal workspace during account registration](https://github.com/plDilipkumar/NexusOps/pull/12), was merged by squash after all four PR checks passed. PR head `480f4158fdbef2802b911d62a2fe70f26d21a224`; merge commit `187f4802bb6c0979d99904860e1407d22d80fcf0`.

Registration previously created an unverified user and verification token but no workspace or owner membership. The verified user could therefore reach an empty workspace list with no self-service recovery path. The new `JdbcWorkspaceProvisioningRepository` creates a unique personal workspace and active OWNER membership inside the same transaction as account creation and verification-token issuance. Failure at any stage rolls the transaction back.

Proposed migration V8 adds only column-scoped INSERT grants for workspace bootstrap. It grants no workspace/membership UPDATE or DELETE and no schema CREATE.

### CI test counts and checks

- Backend: 388 tests, 0 failures, 0 errors; 71 expected database/external-service skips.
- PostgreSQL 17 integration: 71/71 passed, including registration workspace creation and rollback.
- Frontend Node tests: 96/96 passed.
- Vite production build: 1,596 modules transformed.
- Schema contract: V1–V8 applied and verified on disposable PostgreSQL 17; V8 workspace bootstrap and deferred active-owner invariant passed.
- Docker production image build and non-root runtime assertion passed; expected UID/GID is `10001:10001`.
- Browser checks: responsive navigation at 1440, 851, 850, 390, and 320 px; keyboard/skip-link checks; mocked registration, verification, password reset, durable login, identity, workspace switching, logout, expiry and provisional-session revocation.
- All 12 in-flight SQL/EXPLAIN race combinations remain green: sign-out/session expiry × late success/HTTP error/network error.
- Browser auth flows use a mock API. They do not establish live production authentication.

### Temporary Neon migration and login-role proof

Temporary branch `br-shy-star-b31qx5c6` (`neurosql-v7-role-validation-20261104`) was used to apply V8 and verify actual login-role behavior. The temporary `neurosql_auth_probe_login` inherited `neurosql_auth_runtime` with INHERIT TRUE, SET FALSE, ADMIN FALSE. Effective privileges worked; SET ROLE, schema CREATE, display-name reads, workspace-owner updates/deletes, and membership role updates/deletes were denied.

As that temporary login, the test verified login-column reads; personal workspace and OWNER membership creation; session create/find/revoke; password/email-verification updates; action-token insert/consume; shared rate-limit insert/update/delete; query-history insert/read; and saved-query insert/update/delete. After testing, the probe was set NOLOGIN and its group membership revoked. Readback confirmed `rolcanlogin=false` and zero group memberships. Its direct table/schema/default grants had been revoked before the inherited-role test.

V7 and V8 remain unapplied to Neon main.

## Deployment verification

### Render backend

- Service: `neurosql-api`, ID `srv-dbgb6vt9fdbs73da93n0`, Free plan, Singapore.
- Auto-deploy did not create a deployment after the main push. After CI passed and no automatic deployment/event appeared, the latest main commit was deployed explicitly.
- Deployment `dep-dblh208u01pc73a6onbg`: build succeeded at `2026-11-04T11:04:25.234Z`; deployment became LIVE at `2026-11-04T11:05:47.884Z`.
- Logs show the JVM running as a non-root user, Tomcat bound to port 10000, Spring Boot started, and the previous instance shut down gracefully.
- Post-deploy smoke job `126535674767` was rerun after the new deployment became live and passed at `2026-11-04T11:06:20.485Z`: `{"database":"neurosql_db","status":"UP","postgresVersion":"17.11 (9045acd)"}`.
- The smoke request caused Hikari to establish a PostgreSQL connection on the new instance.

### Vercel frontend

- Project: `neurosql`, ID `prj_bWhKWqbbb5EaNRdez05bEsMPe35a`.
- Deployment `dpl_B8mSjJh4Uuy5aizXHxPKz7Dfmg5U`: READY for commit `187f4802`; alias `neurosql.vercel.app` assigned.
- Project metadata still reports `live=false` despite the READY production deployment and assigned alias; treat this as a metadata discrepancy.
- Production environment inventory: zero visible and zero hidden variables. The frontend API URL has a source fallback and feature gates default false.

## Neon production main — unchanged

- Eight V1–V6 tables exist and are empty: `app_users`, `workspaces`, `workspace_memberships`, `user_sessions`, `auth_login_rate_limits`, `account_action_tokens`, `query_history`, and `saved_queries`.
- Production roles remain only `neurosql_owner` (LOGIN with elevated CREATEDB, CREATEROLE, REPLICATION, BYPASSRLS) and `neurosql_auth_runtime` (NOLOGIN/NOINHERIT).
- Read-only verification confirms V8 workspace and membership INSERT privileges are still false on production main.
- No production V7/V8 migration, production LOGIN role, or role membership change was made. The prior explicit approval covered V1–V6 only.

## Release blockers — keep features disabled

1. A dedicated production authentication LOGIN principal inheriting `neurosql_auth_runtime` with INHERIT TRUE, SET FALSE, ADMIN FALSE requires explicit approval and a new credential. Do not use `neurosql_owner` for runtime.
2. Production V7 and V8, plus a separate SQL-read-only LOGIN principal with explicit SELECT grants on approved user-data schemas/tables, require explicit approval. SQL execution and EXPLAIN remain disabled.
3. SMTP credentials and a verified sender/domain are missing; email verification and password-reset delivery remain disabled.
4. Gemini/provider key presence cannot be independently verified through Render environment readback; no live provider request was made. All AI features remain disabled.
5. Production login, email delivery, workspace isolation, SQL execution/history, and live AI behavior are not yet end-to-end verified. Mock browser tests are not a substitute for live auth.
6. Render environment-variable readback is unavailable. The last configuration write set non-secret feature gates false, but current overrides cannot be independently re-read. Keep backend gates disabled; Vercel has no production variables and frontend gates default false.

## Recent security milestones

- PR #9 merged as `b9eb8be029f5f16ae9688234f90dc2b24a2eb683`: session-bound SQL cancellation, EXPLAIN invalidation and session-expiry race hardening.
- PR #10 merged as `3d4d8cf52ab81311a5263ea4f407fa9b790755d4`: non-delegable inherited-only PostgreSQL role memberships.
- PR #11 merged as `9cd10ee84cfe3d7a661bd21dbcecdf057accb294`: Render container runs as UID/GID 10001.
- PR #12 merged as `187f4802bb6c0979d99904860e1407d22d80fcf0`: transactional personal workspace and OWNER membership bootstrap.

No paid resources, destructive production operations, cross-project databases, source-controlled credentials, or unapproved production schema/role changes were introduced.
