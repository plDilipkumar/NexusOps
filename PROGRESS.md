# NeuroSQL Progress

Last verified: 2026-10-27 20:33 UTC

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Vercel project `neurosql` is linked to GitHub repository `plDilipkumar/NexusOps` and uses the Hobby/free plan. Latest production deployment reports `READY` and is aliased to `neurosql.vercel.app`.
- [x] Neon project `neurosql` is on the free organization in Singapore, using PostgreSQL 17. The `main` branch is ready.
- [x] Dedicated Neon database `neurosql_db` is PostgreSQL 17.11. Read-only query on 2026-10-27 found zero visible application tables/views and zero `public.show_db_tree` functions. No schema changes have been made to the main branch.
- [x] React + TypeScript + Vite frontend and Spring Boot 3 / Java 21 backend are present.
- [x] Render service `neurosql-api` is configured in the confirmed workspace on the free plan, Singapore region, repository `plDilipkumar/NexusOps`, branch `main`, and Docker context `backend`; auto-deploy is configured for commits.
- [x] Render deployment `dep-dbgfqe6gekts738l9eng` is live for commit `746d58d`. It was manually triggered after no automatic deployment appeared following the merge.
- [x] GitHub Actions run #31 passed backend `mvn -B verify`, frontend production build, and production API/database smoke test. The smoke test confirmed `status=UP`, database `neurosql_db`, and PostgreSQL `17.11` after the new Render deployment.
- [x] Render logs confirmed the backend started successfully on Java 21 and Tomcat port 10000. The CI smoke test confirmed the live database health response.
- [x] Render environment variables point to the dedicated NeuroSQL Neon database; credentials are not stored in GitHub.
- [x] The frontend polls database health on load and every 60 seconds, with connected/unavailable states.
- [x] Read-only `GET /api/schema` reads PostgreSQL information-schema metadata and groups schemas, tables, and ordered columns. It performs no writes and remains disabled by default.
- [x] PR #4 merged as commit `69d7b53`: added Spring Security, environment-configured single-owner HTTP Basic authentication, BCrypt password hashing in memory, fail-closed authorization for `/api/schema`, public health routes, and CORS restricted to the production frontend and local Vite origin.
- [x] Security tests cover unauthenticated schema access, configured-owner access, public health, CORS preflight, and denial when auth is disabled. GitHub Actions run #19 passed backend `mvn -B verify` and the frontend production build.
- [x] PR #5 merged as commit `deeb794`: added the gated schema-explorer page, owner Basic-auth sign-in, schema/table/column rendering, expandable table columns, refresh and sign-out. Credentials stay in React memory and are never written to browser storage.
- [x] Added `frontend/.env.example` and documented the coordinated frontend/backend flags required to enable schema browsing.
- [x] GitHub Actions run #24 passed backend `mvn -B verify` and the frontend production build. The Vercel preview deployment reported `READY`.
- [x] Production authentication and schema-browser flags remain disabled. No secrets were committed, no production database migrations were run, and no application tables were created on Neon `main`.

## SQL validation guardrail — merged in PR #6
- [x] Added disabled-by-default `POST /api/sql/validate` behind the owner authentication route. It only analyzes drafts; it never executes SQL.
- [x] Validator accepts SELECT/WITH-shaped drafts and conservatively rejects multiple statements, common mutation keywords, SELECT INTO, selected side-effecting PostgreSQL functions, and row-locking clauses. It explicitly warns that it is a lexical heuristic, not a PostgreSQL parser or proof of safety.
- [x] CORS POST permission is scoped to `/api/sql/**`; unrelated API routes remain GET/OPTIONS only.
- [x] Added unit/controller tests for normal SELECT, CTE mutation, multi-statement input, quoted/commented keywords, escape-string boundaries, side-effecting functions, authentication, disabled-auth fail-closed behavior, request validation, and POST CORS preflight.
- [x] PR #6 merged as commit `746d58d`.
- [x] GitHub Actions run #29 passed backend `mvn -B verify` and frontend production build for the feature branch. Run #31 passed backend verification, frontend build, and the post-merge production API/database smoke test.
- [x] Render deployment `dep-dbgfqe6gekts738l9eng` is live for the merged code. The validator remains disabled in production by default; no SQL is executed.

## Durable auth/workspace schema proposal — draft only
- [x] Inspected Neon project `neurosql`, database `neurosql_db`, Vercel project/deployments, Render service/deploy history, and current auth/security code before choosing this milestone.
- [x] Read-only query against Neon `main` confirmed `neurosql_db`, PostgreSQL `17.11`, and zero visible tables. No production schema change was made.
- [x] Created temporary Neon branch `neurosql-auth-migration-check-20261028` (`br-shy-credit-b33ux9py`) from the current `main` branch.
- [x] Applied the proposed DDL only on that temporary branch. It created four tables in `neurosql_app`: `app_users`, `workspaces`, `workspace_memberships`, and `user_sessions`; ten indexes were verified.
- [x] Tested email uniqueness, duplicate workspace slug rejection, one-active-owner enforcement, invalid membership role rejection, session token hash checks, foreign keys, valid session insertion, and schema/table `REVOKE` statements.
- [x] Removed all temporary test rows; all four temporary-branch tables were empty after checks. A separate query confirmed Neon `main` still has zero visible tables.
- [x] Added the proposed SQL under `docs/database/proposed/` so it is not picked up by an application migration runner, plus a schema review document describing invariants, test results, and remaining security work.
- [ ] Draft PR #7 contains the schema proposal and progress update. It is not merged; no migration is approved for Neon `main`.
- [ ] The proposed schema is only a foundation. Session lifecycle, login rate limiting, password policy, email verification/reset, authorization on every protected endpoint, and a separate least-privileged runtime database role remain unimplemented.

## In progress
- [ ] Directly verify browser-side CORS and rendered sign-in/metadata behavior after owner auth and schema browsing are intentionally configured. The login form remains hidden in production by default; no live credentials were supplied or configured.
- [ ] Investigate why Render's auto-deploy did not start after the PR #6 merge. Render reports auto-deploy enabled; that merged commit was manually deployed and verified through the production smoke test.
- [ ] Replace the single-owner in-memory account with durable multi-user authentication and workspace authorization after the proposed schema is approved.
- [ ] Keep the temporary Neon branch available for review/testing until the migration decision is made; do not apply the proposed DDL to Neon `main` without explicit user approval.

## Next milestones
- [ ] Review and approve or revise the proposed auth/workspace schema before any production database change.
- [ ] After approval, add a controlled migration runner and database-backed authentication behind disabled feature flags; test against a temporary Neon branch before production.
- [ ] Implement secure session issuance, hashing, expiry/revocation, login rate limiting, password policy, and workspace membership authorization for every protected endpoint.
- [ ] Add safe read-only SQL execution only after access control, a least-privileged database role, and database-side read-only transaction enforcement are in place; keep query history disabled until its persistence and authorization design is reviewed.
- [ ] Add EXPLAIN performance analysis.
- [ ] Define reviewed schema for saved connections, query history, and audit events only when needed. Prepare and test migrations on a temporary Neon branch and stop for user approval before any main-branch schema change.

## Constraints
- Keep within free-tier usage; no paid resources.
- Never reuse another project's database.
- Never run destructive SQL automatically.
- Do not claim a feature or deployment is complete until verified.
- Read this file before each work session and avoid repeating completed work.
