# NeuroSQL Progress

Last verified: 2026-10-27

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Vercel's linked-project context confirms project `neurosql` is connected to GitHub repository `plDilipkumar/NexusOps`; the project is on the Hobby/free plan.
- [x] Neon project `neurosql` is on the free organization in Singapore, using PostgreSQL 17. The `main` branch is ready.
- [x] The dedicated Neon database is `neurosql_db` on PostgreSQL 17.11. Read-only inspection found zero application tables; the visible `public.show_db_tree` helper function is present. No schema changes have been made.
- [x] Initial React + TypeScript + Vite workspace UI and Spring Boot 3 / Java 21 API exist.
- [x] Render service `neurosql-api` is configured for the confirmed workspace, free plan, Singapore region, repository `plDilipkumar/NexusOps`, branch `main`, and Docker context `backend`.
- [x] Render environment variables point to the dedicated Neon database; credentials are not stored in GitHub.
- [x] `GET /api/health/database` verifies live connectivity and reports database `neurosql_db` and PostgreSQL `17.11` without exposing credentials.
- [x] The frontend polls database health on load and every 60 seconds, with explicit connected/unavailable states.
- [x] Read-only `GET /api/schema` reads PostgreSQL information-schema metadata and groups schemas, tables, and ordered column metadata. It performs no writes and remains disabled by default.
- [x] PR #4 merged as commit `69d7b53`: added Spring Security, environment-configured single-owner HTTP Basic authentication, BCrypt password hashing in memory, fail-closed authorization for `/api/schema`, public health routes, and CORS restricted to the production frontend and local Vite origin.
- [x] Security tests cover unauthenticated schema access, configured-owner access, public health, CORS preflight, and denial when auth is disabled. GitHub Actions run #19 passed backend `mvn -B verify` and the frontend production build; Vercel's PR check passed.
- [x] Production auth and schema flags were not enabled. No Render environment changes, secrets, or database migrations were made for this security change.

## In progress
- [ ] Render did not start a new deployment for merged commit `69d7b53` even though auto-deploy is configured for commits to `main`. The latest recorded live deploy remains commit `3a35086`; manually deploy the current main commit only after confirming no automatic deploy appears.
- [ ] Vercel has started a new production deployment following the merge, but its latest state was still initializing during this check; recheck before claiming it is ready.
- [ ] Directly verify browser-side CORS and the rendered live connection indicator; no actual browser session has been run.

## Next milestones
- [ ] Complete and verify the Render deployment for the merged security boundary while keeping production auth/schema flags off.
- [ ] Add a frontend login flow and verify cross-origin Basic authentication in a real browser before enabling schema browsing.
- [ ] Replace the single-owner in-memory account with durable multi-user authentication and workspace authorization before exposing user-specific data.
- [ ] Connect the schema explorer UI to the authorized schema API.
- [ ] Add safe read-only SQL validation/execution and query history after access control is in place.
- [ ] Add EXPLAIN performance analysis.
- [ ] Define reviewed schema for saved connections, query history, and audit events only when needed. Prepare/test migrations on a temporary Neon branch and request approval before any main-branch schema change.

## Constraints
- Keep within free-tier usage; no paid resources.
- Never reuse another project's database.
- Never run destructive SQL automatically.
- Do not claim a feature or deployment is complete until verified.
- Read this file before each work session and avoid repeating completed work.
