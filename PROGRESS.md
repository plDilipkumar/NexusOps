# NeuroSQL — release progress

Last verified: 2026-11-04 11:59 UTC. Timestamps below use UTC. Earlier detailed run notes remain in Git history; this file is kept concise to avoid stale duplicated status sections.

## Current release state

- Repository: [plDilipkumar/NexusOps](https://github.com/plDilipkumar/NexusOps)
- Current application commit: `2d20070500ae8cc4b194eabfe0f3fb99915b8f97` — PR #13, fail-closed durable-auth database identity guard.
- Previous tracker commit: `51186497baffdbc5dc645facc0dc44ae8c2947a9`; PR #13 merge commit is the latest application-code commit.
- Open PRs: 0.
- Last verified main CI: run #483 (`41817996488`), all five jobs passed. PR #13 CI run #485 (`41824714562`) passed all four PR jobs; main push CI for merge commit is not surfaced by the available workflow-run listing. PR-run production smoke was skipped as expected.
- Current backend deployment: Render `dep-dblh208u01pc73a6onbg`, LIVE, for application commit `187f4802`.
- Latest frontend deployment: Vercel `dpl_4f8THEVF6NHk3shy3RSDHdKD6WwT`, READY, for application commit `2d200705`; verified alias `neurosql.vercel.app`.
- Production database: Neon project `neurosql` (`lingering-dew-88806235`), main branch `br-empty-credit-b360dmpc`, database `neurosql_db`, PostgreSQL 17.11, Free plan.

## Latest application milestone — personal workspace bootstrap

PR #12, [Create a personal workspace during account registration](https://github.com/plDilipkumar/NexusOps/pull/12), was merged by squash after all four PR checks passed. PR head `480f4158fdbef2802b911d62a2fe70f26d21a224`; merge commit `187f4802bb6c0979d99904860e1407d22d80fcf0`.

Registration creates the unverified account, unique personal workspace, active OWNER membership, and verification-token digest in one transaction. Failure at any stage rolls the transaction back. Proposed migration V8 adds only column-scoped INSERT grants for this bootstrap; it grants no ownership/membership updates, deletes, or schema CREATE.

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

### Latest main CI job verification

Run #483 jobs were fetched individually; all five jobs completed successfully:
- Frontend production build: job `126535662729`.
- Backend tests and production Docker/non-root assertion: job `126535663507`.
- Production API/database smoke: job `126535674767`.
- Proposed auth schema and runtime grants (ephemeral PostgreSQL 17): job `126535717569`.
- Durable-auth/read-only SQL integration (PostgreSQL 17): job `126535722753`.

The post-deployment smoke job completed at `2026-11-04T11:06:20.485Z` and returned `status=UP`, database `neurosql_db`, PostgreSQL `17.11 (9045acd)`. Render request-log lookup for `/api/health/database` returned no request entries in the 11:00–11:45 UTC window, so the GitHub smoke job remains the direct post-deploy health evidence; no separate live HTTP probe was possible from this runner.

## Temporary Neon migration and least-privilege proof

Temporary branch `br-shy-star-b31qx5c6` (`neurosql-v7-role-validation-20261104`) contains V1–V8 and the V7 read-only group role. Fresh read-only checks confirm:
- V8 column-level workspace and owner-membership INSERT privileges are present for `neurosql_auth_runtime`.
- Auth runtime cannot UPDATE membership role or CREATE in `neurosql_app`.
- `neurosql_sql_readonly` can CONNECT but cannot SELECT from `neurosql_app.app_users`.
- Both temporary probe LOGIN principals now have `rolcanlogin=false` and no membership in either runtime group. The Neon role-list display still labels their authentication method as password; PostgreSQL `pg_roles` readback confirms login is disabled.

The temporary probe verified allowed SELECT access to the explicitly granted probe table and denied writes, DDL, authentication-schema reads, and `SET ROLE`. No production main changes were made during this audit.

## Production deployment verification

### Render backend

- Service: `neurosql-api`, ID `srv-dbgb6vt9fdbs73da93n0`, Free plan, Singapore, branch `main`, auto-deploy setting `yes`.
- Current deployment: `dep-dblh208u01pc73a6onbg`, LIVE for commit `187f4802`; build succeeded at `2026-11-04T11:04:25.234Z`, deployment became LIVE at `2026-11-04T11:05:47.884Z`.
- Post-deploy GitHub smoke job `126535674767` passed at `2026-11-04T11:06:20.485Z`.
- Application error-log query for 11:00–11:45 UTC returned no error entries.
- Render environment-variable readback is not available through the connected interface. The last configuration write set the non-secret feature gates false; current overrides cannot be independently re-read.

### Vercel frontend

- Project: `neurosql`, ID `prj_bWhKWqbbb5EaNRdez05bEsMPe35a`.
- Latest deployment `dpl_33XUTjBFqq6hPt91huLUfNBB3tHT`: READY, production target, aliases include `neurosql.vercel.app`, Git commit `bd6354c`.
- Custom domain `neurosql.vercel.app` is verified. SSO protection is configured for all deployment domains except custom domains.
- Project metadata still reports `live=false` despite the READY production deployment and verified alias; treat this as a metadata discrepancy, not a failed deployment.
- Production environment inventory: zero visible and zero hidden variables. The frontend API URL has a source fallback and feature gates default false.

## Neon production main — unchanged

- Project plan: `free_v3`; AWS Singapore; PostgreSQL 17; default compute 0.25 CU. Seven branches are listed against the project's ten-branch limit; no branch was created during this run.
- Eight V1–V6 tables exist and are empty: `app_users`, `workspaces`, `workspace_memberships`, `user_sessions`, `auth_login_rate_limits`, `account_action_tokens`, `query_history`, and `saved_queries`.
- Production roles: `neurosql_owner` (LOGIN, CREATEDB, CREATEROLE, REPLICATION, BYPASSRLS) and `neurosql_auth_runtime` (NOLOGIN, no elevated attributes). The owner currently has a non-inheriting, non-SET, ADMIN-option membership in the auth group; it is not a runtime login and must not be used as one.
- Read-only SQL confirms V8 workspace/membership INSERT privileges are still false on production main. `neurosql_sql_readonly` does not exist on production main.
- No production V7/V8 migration, production LOGIN role, or role membership change was made. Earlier explicit approval covered V1–V6 only.

## Release blockers — keep features disabled

1. A dedicated production authentication LOGIN principal inheriting `neurosql_auth_runtime` with `INHERIT TRUE, SET FALSE, ADMIN FALSE` requires explicit approval and a new credential.
2. Production V7 and V8, plus a separate SQL-read-only LOGIN principal with explicit SELECT grants on approved user-data schemas/tables, require explicit approval. SQL execution and EXPLAIN remain disabled.
3. SMTP credentials and a verified sender/domain are missing; email verification and password-reset delivery are not operational.
4. Gemini/provider key presence cannot be independently verified through Render environment readback; no live provider request was made. All AI features remain disabled.
5. Production login, email delivery, workspace isolation, SQL execution/history, and live AI behavior are not end-to-end verified. Mock browser tests are not a substitute for live auth.
6. Vercel has no production environment variables; the frontend currently relies on its API URL source fallback and disabled-by-default feature flags.

## Recent security milestones

- PR #9 merged as `b9eb8be029f5f16ae9688234f90dc2b24a2eb683`: session-bound SQL cancellation, EXPLAIN invalidation and session-expiry race hardening.
- PR #10 merged as `3d4d8cf52ab81311a5263ea4f407fa9b790755d4`: non-delegable inherited-only PostgreSQL role memberships.
- PR #11 merged as `9cd10ee84cfe3d7a661bd21dbcecdf057accb294`: Render container runs as UID/GID 10001.
- PR #12 merged as `187f4802bb6c0979d99904860e1407d22d80fcf0`: transactional personal workspace and OWNER membership bootstrap.

## Run 7 audit — 2026-11-04 11:45 UTC

- Re-read this tracker and inspected main commits, open/recent PRs, main CI jobs, Neon project/main/temporary branch, Vercel project/deployment/domain/env inventory, and Render service/deployment/events/logs.
- No new application commit or open PR appeared after PR #12. No code changes were made in this audit.
- Fresh production role/table/privilege readback confirmed all eight tables remain empty; production V8 grants and V7 SQL role remain absent.
- Fresh temporary-branch checks reconfirmed V7/V8 least-privilege behavior and that the two probe principals are NOLOGIN with no runtime-group memberships.
- The Vercel tracker deployment `dpl_33XUTjBFqq6hPt91huLUfNBB3tHT` is READY and the custom domain is verified. Render deployment `dep-dblh208u01pc73a6onbg` remains LIVE. No error-level application logs were returned for the audit window.
- No paid resources, destructive operations, cross-project database reuse, source-controlled credentials, or unapproved production schema/role changes were introduced.


## Run 7 continuation — PR #13 durable-auth database identity guard

PR #13, [Fail closed when durable auth uses an unsafe database identity](https://github.com/plDilipkumar/NexusOps/pull/13), was merged by squash after all four PR checks passed.

- PR head: `3935b3b0ac8061e4c78223e04e2e2efa214c6fad`.
- Merge commit: `2d20070500ae8cc4b194eabfe0f3fb99915b8f97`.
- PR CI run #485: `41824714562`.
- Backend job `126556395196`: 391 tests, 0 failures, 0 errors, 71 expected skips; includes 3 new guard unit tests. Production Docker build and UID/GID 10001 assertion passed.
- PostgreSQL 17 integration job `126556395295`: 71/71 passed.
- Auth schema/role contract job `126556395317`: passed, including the new disposable LOGIN membership contract.
- Frontend job `126556394774`: 96/96 unit tests, production build, responsive and mocked account/session browser tests passed.
- Production smoke was skipped for the PR event; the last post-deploy smoke remains job `126535674767` for the previous Render deployment.

The new `DurableAuthDatabaseRoleGuard` runs only when durable sessions are enabled and outside the disposable `test` profile. It requires the datasource's `session_user` to be a non-privileged LOGIN with exactly one membership in `neurosql_auth_runtime`, using inherited privileges but no SET ROLE or ADMIN OPTION, and no schema CREATE. It refuses startup if the datasource uses the elevated database owner. The first CI attempt correctly exposed that existing Spring integration tests used the admin login; the tests were moved to the explicit `test` profile, then the full PR run #485 passed. Unit tests cover accept/reject/missing-role cases, while the disposable PostgreSQL contract verifies the actual PG17 membership options.

### Post-merge deployment state

- Vercel deployment `dpl_4f8THEVF6NHk3shy3RSDHdKD6WwT` is READY for merge commit `2d200705`, with aliases including `neurosql.vercel.app`.
- Render service still reports auto-deploy enabled on `main`, but no deployment for `2d200705` appeared in the deploy list after the merge and a two-minute observation window. The current LIVE backend remains deployment `dep-dblh208u01pc73a6onbg` for commit `187f4802`. No manual deploy was triggered because Render's service is configured for automatic commit deploys; the latest backend commit therefore still needs live deployment verification.
- GitHub combined commit status reports Vercel success. The connected workflow-run listing only exposes pull-request-triggered runs, so no claim is made that the post-merge push CI or production smoke completed.

No Neon production schema or roles were changed. The durable-auth guard is conditional and has not been activated in production. Production auth/SQL/AI/email gates remain disabled pending dedicated LOGIN role approval, V7/V8 production approval, SMTP credentials, and verifiable Gemini/provider credentials.
