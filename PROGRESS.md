# NeuroSQL Progress

Last verified: 2026-10-27 18:37 UTC

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Vercel project `neurosql` is linked to GitHub repository `plDilipkumar/NexusOps` and uses the Hobby/free plan.
- [x] Neon project `neurosql` is on the free organization in Singapore, using PostgreSQL 17. The `main` branch is ready.
- [x] Dedicated Neon database `neurosql_db` is PostgreSQL 17.11. Read-only inspection found zero application tables; the visible `public.show_db_tree` helper function is present. No schema changes have been made.
- [x] React + TypeScript + Vite frontend and Spring Boot 3 / Java 21 backend are present.
- [x] Render service `neurosql-api` is configured in the confirmed workspace on the free plan, Singapore region, repository `plDilipkumar/NexusOps`, branch `main`, and Docker context `backend`.
- [x] Render environment variables point to the dedicated NeuroSQL Neon database; credentials are not stored in GitHub.
- [x] `GET /api/health/database` was verified by an earlier production smoke test to report `neurosql_db` and PostgreSQL `17.11` without exposing credentials.
- [x] The frontend polls database health on load and every 60 seconds, with connected/unavailable states.
- [x] Read-only `GET /api/schema` reads PostgreSQL information-schema metadata and groups schemas, tables, and ordered columns. It performs no writes and remains disabled by default.
- [x] PR #4 merged as commit `69d7b53`: added Spring Security, environment-configured single-owner HTTP Basic authentication, BCrypt password hashing in memory, fail-closed authorization for `/api/schema`, public health routes, and CORS restricted to the production frontend and local Vite origin.
- [x] Security tests cover unauthenticated schema access, configured-owner access, public health, CORS preflight, and denial when auth is disabled. GitHub Actions run #19 passed backend `mvn -B verify` and the frontend production build.
- [x] Render deployment `dep-dbge2159fdbs73d76ls0` is live. Logs confirmed Spring Boot startup and a Hikari PostgreSQL connection.
- [x] PR #5 merged as commit `deeb794`: added the gated schema-explorer page, owner Basic-auth sign-in, schema/table/column rendering, expandable table columns, refresh and sign-out. Credentials stay in React memory and are never written to browser storage.
- [x] Added `frontend/.env.example` and documented the coordinated frontend/backend flags required to enable schema browsing.
- [x] GitHub Actions run #24 passed backend `mvn -B verify` and the frontend production build. The Vercel preview deployment reported `READY`.
- [x] Vercel production deployment for commit `deeb794` reports `READY` and is aliased to `neurosql.vercel.app`.
- [x] Production authentication and schema-browser flags remain unchanged and disabled. No secrets were committed, no database migrations were run, and no application tables were created.

## In progress
- [ ] Directly verify browser-side CORS and rendered sign-in/metadata behavior after owner auth and schema browsing are intentionally configured. The login form remains hidden in production by default; no live credentials were supplied or configured.
- [ ] Confirm the post-merge production API/database smoke-test result. Direct opening of the public API endpoint was unavailable through the current HTTP tools; the last independently verified production smoke test was earlier.
- [ ] Investigate why Render's auto-deploy did not start for an earlier backend change. Render still reports auto-deploy enabled; the latest merge changed only frontend/docs/progress files, so a new backend build was not required for that merge.
- [ ] Replace the single-owner in-memory account with durable multi-user authentication and workspace authorization before exposing user-specific data.

## Next milestones
- [ ] After deliberate owner credential setup and coordinated environment configuration, verify cross-origin Basic authentication and schema browsing end-to-end before enabling it for users.
- [ ] Add safe read-only SQL validation/execution and query history only after access control is in place.
- [ ] Add EXPLAIN performance analysis.
- [ ] Define reviewed schema for saved connections, query history, and audit events only when needed. Prepare and test migrations on a temporary Neon branch and stop for user approval before any main-branch schema change.

## Constraints
- Keep within free-tier usage; no paid resources.
- Never reuse another project's database.
- Never run destructive SQL automatically.
- Do not claim a feature or deployment is complete until verified.
- Read this file before each work session and avoid repeating completed work.
