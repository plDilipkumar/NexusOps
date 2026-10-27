# NeuroSQL Progress

Last verified: 2026-10-27 17:38 UTC

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Vercel's linked-project context confirms project `neurosql` is connected to GitHub repository `plDilipkumar/NexusOps`; the project is on the Hobby/free plan.
- [x] Neon project `neurosql` is on the free organization in Singapore, using PostgreSQL 17. The `main` branch is ready.
- [x] The dedicated Neon database is `neurosql_db` on PostgreSQL 17.11. Read-only inspection found zero application tables; the visible `public.show_db_tree` helper function is present. No schema changes have been made.
- [x] Initial React + TypeScript + Vite workspace UI and Spring Boot 3 / Java 21 API exist.
- [x] Render service `neurosql-api` is configured for the confirmed workspace, free plan, Singapore region, repository `plDilipkumar/NexusOps`, branch `main`, and Docker context `backend`.
- [x] Render environment variables point to the dedicated Neon database; credentials are not stored in GitHub.
- [x] `GET /api/health/database` was previously verified to report `neurosql_db` and PostgreSQL `17.11` without exposing credentials.
- [x] The frontend polls database health on load and every 60 seconds, with explicit connected/unavailable states.
- [x] Read-only `GET /api/schema` reads PostgreSQL information-schema metadata and groups schemas, tables, and ordered column metadata. It performs no writes and remains disabled by default.
- [x] PR #4 merged as commit `69d7b53`: added Spring Security, environment-configured single-owner HTTP Basic authentication, BCrypt password hashing in memory, fail-closed authorization for `/api/schema`, public health routes, and CORS restricted to the production frontend and local Vite origin.
- [x] Security tests cover unauthenticated schema access, configured-owner access, public health, CORS preflight, and denial when auth is disabled. GitHub Actions run #19 passed backend `mvn -B verify` and the frontend production build; Vercel's PR check passed.
- [x] Render auto-deploy did not start after the merge or subsequent progress commit. After confirming no new deployment existed, a manual deploy was triggered for commit `f68cd1f`. Render reports deployment `dep-dbge2159fdbs73d76ls0` as `live` (finished 17:38:20 UTC).
- [x] Render's Docker build completed `mvn -B verify` successfully. Runtime logs show Spring Boot started and Hikari opened a PostgreSQL connection. Direct retrieval of the health endpoint response was unavailable through the HTTP tools in this run, so that response is not newly claimed as verified.
- [x] Production auth and schema flags remain off. No secrets were committed, no Render auth variables were changed, and no database migrations or schema changes were made.

## In progress
- [ ] Re-verify `GET /api/health/database` after the latest Render deployment using a direct browser or successful production smoke-test result.
- [ ] Directly verify browser-side CORS and the rendered live connection indicator; no actual browser session has been run.
- [ ] Investigate why Render's configured auto-deploy did not trigger for commits to `main`; manual deployment restored the current service but does not solve automatic deploy reliability.
- [ ] The latest Vercel production deployment reports `READY`; the app still has no login flow and the schema explorer is not connected to the API.

## Next milestones
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
