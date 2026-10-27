# NeuroSQL Progress

Last verified: 2026-10-28 04:03 IST

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
