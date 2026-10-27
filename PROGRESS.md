# NeuroSQL Progress

Last verified: 2026-10-28 05:00 IST

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Vercel project `neurosql` is linked to GitHub repository `plDilipkumar/NexusOps` and uses the Hobby/free plan. Production deployment for commit `746d58d` reports `READY` and is aliased to `neurosql.vercel.app`.
- [x] Neon project `neurosql` is on the free organization in Singapore, using PostgreSQL 17. The `main` branch is ready.
- [x] Dedicated Neon database `neurosql_db` is PostgreSQL 17.11. Current read-only inspection found zero visible application tables/views; the existing `public.show_db_tree` helper function is present. No production schema changes have been made.
- [x] React + TypeScript + Vite frontend and Spring Boot 3 / Java 21 backend are present.
- [x] Render service `neurosql-api` is configured in the confirmed workspace on the free plan, Singapore region, repository `plDilipkumar/NexusOps`, branch `main`, and Docker context `backend`; auto-deploy is configured for commits.
- [x] Render deployment `dep-dbgfqe6gekts738l9eng` is live for commit `746d58d`. It was manually triggered after no automatic deployment appeared following the merge.
- [x] GitHub Actions run #31 passed backend `mvn -B verify`, frontend production build, and production API/database smoke test. The smoke test confirmed `status=UP`, database `neurosql_db`, and PostgreSQL `17.11` after the new Render deployment.
- [x] Render logs confirmed the new backend started successfully on Java 21 and Tomcat port 10000. The CI smoke test then confirmed the live database health response.
- [x] Render environment variables point to the dedicated NeuroSQL Neon database; credentials are not stored in GitHub.
- [x] The frontend polls database health on load and every 60 seconds, with connected/unavailable states.
- [x] Read-only `GET /api/schema` reads PostgreSQL information-schema metadata and groups schemas, tables, and ordered columns. It performs no writes and remains disabled by default.
- [x] PR #4 merged as commit `69d7b53`: added Spring Security, environment-configured single-owner HTTP Basic authentication, BCrypt password hashing in memory, fail-closed authorization for `/api/schema`, public health routes, and CORS restricted to the production frontend and local Vite origin.
- [x] Security tests cover unauthenticated schema access, configured-owner access, public health, CORS preflight, and denial when auth is disabled. GitHub Actions run #19 passed backend `mvn -B verify` and the frontend production build.
- [x] PR #5 merged as commit `deeb794`: added the gated schema-explorer page, owner Basic-auth sign-in, schema/table/column rendering, expandable table columns, refresh and sign-out. Credentials stay in React memory and are never written to browser storage.
- [x] Added `frontend/.env.example` and documented the coordinated frontend/backend flags required to enable schema browsing.
- [x] GitHub Actions run #24 passed backend `mvn -B verify` and the frontend production build. The Vercel preview deployment reported `READY`.
- [x] Production authentication and schema-browser flags remain disabled. No secrets were committed, no database migrations were run, and no application tables were created.

## SQL validation guardrail — merged in PR #6
- [x] Added disabled-by-default `POST /api/sql/validate` behind the owner authentication route. It only analyzes drafts; it never executes SQL.
- [x] Validator accepts SELECT/WITH-shaped drafts and conservatively rejects multiple statements, common mutation keywords, SELECT INTO, selected side-effecting PostgreSQL functions, and row-locking clauses. It explicitly warns that it is a lexical heuristic, not a PostgreSQL parser or proof of safety.
- [x] CORS POST permission is scoped to `/api/sql/**`; unrelated API routes remain GET/OPTIONS only.
- [x] Added unit/controller tests for normal SELECT, CTE mutation, multi-statement input, quoted/commented keywords, escape-string boundaries, side-effecting functions, authentication, disabled-auth fail-closed behavior, request validation, and POST CORS preflight.
- [x] PR #6 merged as commit `746d58d`.
- [x] GitHub Actions run #29 passed backend `mvn -B verify` and frontend production build for the feature branch. Run #31 passed backend verification, frontend build, and the post-merge production API/database smoke test.
- [x] Render deployment `dep-dbgfqe6gekts738l9eng` is live for the merged code. The validator remains disabled in production by default; no SQL is executed.

## In progress
- [ ] Directly verify browser-side CORS and rendered sign-in/metadata behavior after owner auth and schema browsing are intentionally configured. The login form remains hidden in production by default; no live credentials were supplied or configured.
- [ ] Investigate why Render's auto-deploy did not start after the PR #6 merge. Render reports auto-deploy enabled; this run manually deployed the merged commit and verified it through the production smoke test.
- [ ] Replace the single-owner in-memory account with durable multi-user authentication and workspace authorization before exposing user-specific data.

## Next milestones
- [ ] After deliberate owner credential setup and coordinated environment configuration, verify cross-origin Basic authentication and schema browsing end-to-end before enabling them for users.
- [ ] Replace the single-owner in-memory account with durable multi-user authentication and workspace authorization before enabling user-specific data.
- [ ] Add safe read-only SQL execution only after access control, a least-privileged database role, and read-only transaction enforcement are in place; keep query history disabled until its persistence and authorization design is reviewed.
- [ ] Add EXPLAIN performance analysis.
- [ ] Define reviewed schema for saved connections, query history, and audit events only when needed. Prepare and test migrations on a temporary Neon branch and stop for user approval before any main-branch schema change.

## Constraints
- Keep within free-tier usage; no paid resources.
- Never reuse another project's database.
- Never run destructive SQL automatically.
- Do not claim a feature or deployment is complete until verified.
- Read this file before each work session and avoid repeating completed work.


## Durable-session token primitive — feature branch only
- [x] Re-inspected this progress file, the current security code, Neon project/branches/database, Vercel project and recent production deployments, and Render service/deploy history before selecting this step.
- [x] Added `SessionTokenCodec` on branch `feature/session-token-codec-main`: generates 32-byte cryptographically random URL-safe bearer tokens and hashes canonical tokens to lowercase SHA-256 hex compatible with the proposed `user_sessions.token_hash` column.
- [x] Added unit tests for token shape, uniqueness across 128 generated tokens, deterministic hash output, hash format, and malformed/non-canonical input rejection.
- [x] GitHub Actions run #44 (`40602859467`) passed backend `mvn -B verify` (including the new token-codec tests) and the frontend production build. Production API/database smoke was skipped as expected for a pull request.
- [ ] The codec is intentionally not wired into HTTP authentication or persistence. The proposed durable-auth schema remains in draft PR #7 and has not been approved or applied to Neon `main`.
- [x] Current infrastructure inspection: Neon default branch `br-empty-credit-b360dmpc` is ready; Vercel production deployment for commit `6425ab4` reports `READY`; Vercel preview for token-codec commit `853225f` reports `READY`; Render free service `neurosql-api` remains live for commit `746d58d`, with auto-deploy configured.
- [x] Render application logs show the Java 21 / Spring Boot service started successfully and initialized its database pool during the last deployment. No new backend deployment or environment change was needed for this isolated token primitive.
- [ ] Render auto-deploy reliability remains an open issue: the last feature merge needed a manual deploy even though auto-deploy is enabled.
- [x] No production database tables were created or modified, no secrets were added to GitHub, and no paid resources were introduced.
