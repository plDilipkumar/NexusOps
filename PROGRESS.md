# NeuroSQL Progress

Last verified: 2026-10-28 10:00 IST

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Vercel project `neurosql` is linked to GitHub repository `plDilipkumar/NexusOps` and uses the Hobby/free plan. Latest inspected production deployment for commit `6425ab4` reports `READY` and the project includes the `neurosql.vercel.app` alias.
- [x] Neon project `neurosql` is on the free organization in Singapore, using PostgreSQL 17. The `main` branch is ready.
- [x] Dedicated Neon database `neurosql_db` is PostgreSQL 17.11. A direct read-only query on 2026-10-28 found zero visible application tables, zero views, and zero `public.show_db_tree` functions. No production schema changes have been made.
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


## Fixed session lifetime policy — PR #9 (not merged)
- [x] Re-read the default-branch progress tracker and inspected the current open PRs before continuing. PR #9 remains open against `main`; PR #7 remains a draft schema proposal and has not been approved.
- [x] Added `SessionLifetimePolicy` on `feature/session-token-codec-main`: sessions have a fixed 12-hour maximum lifetime, are not sliding, and are considered active only when unrevoked, unexpired, issued no later than the current clock, and expiring no later than the maximum lifetime from issuance.
- [x] Added deterministic tests for fixed expiry, active session, expiry boundary, revocation, future issue timestamps, overlong lifetime, inconsistent/missing timestamps, and null issue-time rejection.
- [x] The first CI attempt (#47) caught invalid use of nonexistent `Instant.plusHours/minusHours/minusMinutes` helpers in tests. Replaced them with supported second-based arithmetic; latest GitHub Actions run #48 (`40608838717`) passed backend Maven verification and frontend production build. Production smoke test was skipped as expected for a pull request.
- [x] Vercel preview for fixed test commit `fe87f8782c02d5dca6fd094237b9714970e7b02b` reports `READY`.
- [x] Neon project `neurosql` is on the free tier in Singapore, PostgreSQL 17. The ready `main` branch contains database `neurosql_db`; table listing is empty and the branch tree shows only the existing `public.show_db_tree` helper function. No database changes were made.
- [x] Render service `neurosql-api` is configured on the free plan in Singapore, linked to `plDilipkumar/NexusOps` branch `main`, with commit-triggered auto-deploy enabled. Latest listed live deployment remains commit `746d58d`; this branch only changes Java utility/tests/docs, so no backend deployment was triggered.
- [ ] The session lifetime policy is a tested utility only; it is not wired to login, HTTP authentication, or database persistence. Durable authentication remains blocked on review/approval of draft PR #7 and implementation of the session lifecycle, authorization, rate limiting, and least-privileged database access.
- [ ] Render's earlier missed auto-deploy remains unresolved. No new backend deployment or production smoke test was expected for this pull-request-only change.
- [x] No secrets, paid resources, production environment changes, or database migrations were introduced.


## Configured-owner password policy — PR #9 (not merged)
- [x] Re-read this tracker and inspected the current security configuration, open PRs, Neon project/branches/database, Vercel project/deployments, and the Render service/deploy history before choosing this step.
- [x] Added PasswordPolicy, applied when the environment-configured owner account is enabled: minimum 12 Unicode code points, maximum 72 UTF-8 bytes to avoid BCrypt input truncation ambiguity, and no ISO control characters. Passwords are not trimmed or normalized.
- [x] Added tests for short/missing values, the BCrypt byte boundary (72 accepted, 74 rejected), control characters, and configured-owner BCrypt hashing/weak-password rejection.
- [x] GitHub Actions run #51 (`40615489270`) passed backend Maven verification and frontend production build for code commit `9e39c87`; the final documentation commit `c86ede4` did not change executable code. The latest Vercel preview for `c86ede4` reports `READY`.
- [ ] This is a configuration guard only; there is still no registration, password-reset, or multi-user login endpoint. Authentication remains disabled in production.
- [x] Current direct Neon main-branch read-only query reports database neurosql_db, PostgreSQL 17.11, zero visible tables/views, and zero public.show_db_tree functions; this corrects the earlier progress statement that the helper function existed.
- [x] Vercel project neurosql is linked to the NexusOps repo; latest listed deployment for PR #9 head ab09c993 reports READY. Production alias remains neurosql.vercel.app.
- [x] Render service neurosql-api is in the confirmed Dilip Kumar workspace, free plan, Singapore, linked to repo plDilipkumar/NexusOps branch main, auto-deploy trigger commit; latest listed live deploy remains 746d58d. No backend code on main changed in this run, so no Render deployment was triggered.
- [ ] Render auto-deploy reliability remains unresolved: previous feature merge required manual deployment despite the setting being enabled.
- [ ] PR #7 durable-auth schema is still draft and unapproved. No schema migration was applied to Neon main; no credentials, paid resources, or production environment changes were introduced.

## Basic-auth brute-force rate limit — PR #9 (not merged)
- [x] Re-read the default-branch progress tracker and inspected the current feature branch, open PR #9, Neon project/branches/database, Vercel repository linkage/deployments, and Render service/deploy history before selecting this step.
- [x] Added a bounded, process-local Basic-auth attempt limiter: five failed credentials for the same normalized username within a rolling 15-minute window trigger a 15-minute block. Blocked requests return HTTP 429 with a Retry-After header and a non-cacheable generic error response.
- [x] Wired the limiter into the Spring Security chain only when `neurosql.auth.enabled=true`, and only for `/api/schema/**` and `/api/sql/**`. Requests without a Basic Authorization header are not counted. Forwarded client-IP headers are not trusted; this avoids treating a shared reverse-proxy IP as one global identity.
- [x] The in-memory state is capped at 10,000 identities and successful authentication clears that username's prior failures. This is defense-in-depth for the current single-instance configuration, not a distributed limiter; it resets on restart and must be reviewed before multi-user/multi-instance use.
- [x] Added deterministic unit tests for the rolling window, threshold, retry delay, success reset, and lockout expiry, plus a MockMvc test that verifies repeated invalid Basic credentials return 401 and then 429.
- [x] GitHub Actions run #58 (`40621859681`) passed backend `mvn -B verify` (45 tests, zero failures) and frontend production build. The production API/database smoke test was skipped as expected for a pull-request run.
- [x] Follow-up GitHub Actions run #59 (`40621956278`) passed after the progress update; Vercel preview for that exact documentation commit `ed727bc1421fcc31eabce230670859274fa191ec` reports `READY`.
- [x] Vercel preview for commit `8c3f70881d309a197a03606a3c236cd618bba2dc` reports `READY`; the project query filtered by the GitHub repository returned the `neurosql` project linked to `plDilipkumar/NexusOps`.
- [x] Render service `neurosql-api` remains on the free plan, linked to `plDilipkumar/NexusOps` branch `main`, with commit auto-deploy configured. Latest listed live deployment remains commit `746d58da4d47b1fb49a6796dda8b424c8a6c969d`; no backend deployment was triggered for this unmerged feature branch.
- [x] Neon project `neurosql` remains on the free tier in Singapore, PostgreSQL 17. Production branch `main` is ready and database `neurosql_db` exists. A fresh table listing returned no tables; the branch tree currently shows the existing `public.show_db_tree` helper. No schema changes or migration were made.
- [ ] The limiter is not enabled in production because owner authentication remains disabled. It is username-based and process-local; before enabling durable multi-user auth, revisit account-lockout abuse, shared/distributed rate limits, and durable session lifecycle.
- [ ] Draft PR #7 still contains the unapproved durable-auth schema proposal. It has not been merged or applied to Neon `main`.
- [ ] Render auto-deploy reliability remains unresolved; the current run did not change `main` or trigger a backend deployment.


## Feature-gated JDBC session repository — PR #9 (not merged)
- [x] Re-read the default-branch progress tracker and inspected the current feature branch, open PRs, Neon project/branches/database, Vercel Git linkage and deployments, and Render service/deploy history before selecting this step.
- [x] Added `JdbcSessionRepository` on `feature/session-token-codec-main`. The Spring bean is disabled unless `NEUROSQL_AUTH_DURABLE_SESSIONS_ENABLED=true`; the flag has not been configured in production.
- [x] Session creation accepts only a lowercase 64-character SHA-256 token digest, validates that expiry is after issuance and no more than twelve hours later, and persists the digest rather than a raw bearer token.
- [x] Active-session lookup is parameterized and filters out revoked sessions, future-issued sessions, expired or overlong sessions, and sessions belonging to non-ACTIVE users.
- [x] Revocation is idempotent and uses `GREATEST(revoked_at, created_at)` semantics to avoid violating the proposed schema's timestamp constraint if the caller clock is behind.
- [x] Added repository tests using a recording `JdbcTemplate` for parameterized insert/query/update behavior, malformed digest rejection, fixed lifetime checks, active-account filters, and idempotent revocation.
- [x] GitHub Actions run #62 (`40627671686`) passed backend `mvn -B verify` and the frontend production build. The production API/database smoke job was skipped, as expected for a pull request.
- [x] Vercel preview for the tested commit `b8f26b1905770c1907f6587096de68364b7076c3` reports `READY`.
- [x] Direct infrastructure inspection confirmed Neon project `neurosql`, PostgreSQL 17, database `neurosql_db`; the production `main` branch still has zero application tables. No migration was applied and no production rows/schema were changed.
- [x] Vercel project `neurosql` remains linked to `plDilipkumar/NexusOps` on the Hobby plan. Render service `neurosql-api` remains on the free plan, linked to repository branch `main`, with the last listed live deployment on commit `746d58d`. No Render deploy or environment change was triggered for this feature-branch-only work.
- [ ] This repository adapter is not wired to login, HTTP authentication, or a session controller, and its SQL has not yet been integration-tested against the temporary Neon schema branch. It remains gated off until the schema proposal in draft PR #7 is approved and a least-privileged runtime database role is configured.
- [ ] Render's prior missed auto-deploy remains unresolved. No production smoke test was expected for this pull-request-only change.
- [x] No credentials, paid resources, or production database changes were introduced.


## Session lifecycle orchestration — PR #9 (not merged)
- [x] Re-read the default-branch progress tracker and active feature-branch tracker, inspected the open authentication/schema proposals, and checked Neon, Vercel, and Render before choosing this step.
- [x] Added `SessionLifecycleService`, gated by `NEUROSQL_AUTH_DURABLE_SESSIONS_ENABLED=true`. It is not wired to a controller, login, or the Spring Security filter chain, and the flag remains unset in production.
- [x] Session issuance generates a canonical 256-bit URL-safe token, derives its SHA-256 digest, stores only the digest through `JdbcSessionRepository`, and returns the raw token once with a fixed 12-hour expiry. The issued-session record redacts the token from `toString()` to reduce accidental log disclosure.
- [x] Session validation hashes canonical tokens and delegates active-session checks to the repository; malformed tokens and missing timestamps fail closed without database calls. Revocation also delegates by digest and rejects malformed tokens or missing timestamps.
- [x] Added four service tests covering digest-only persistence, fixed expiry, token redaction, invalid issue inputs, digest-based validation, and digest-based revocation.
- [x] GitHub Actions run #64 (`40632424691`) passed backend Maven verification and frontend production build for the service implementation. Run #65 (`40632439757`) passed backend Maven verification and frontend production build including the new service tests; the production smoke job was skipped as expected for a pull-request run.
- [x] Vercel preview for test commit `e2699e3bfbb0140e07e12fd452648c9ae633d6ee` reports `READY`; the Vercel project is linked to `plDilipkumar/NexusOps`.
- [x] Neon project `neurosql` is the dedicated free-tier PostgreSQL 17 project in Singapore. Production branch `main` (`br-empty-credit-b360dmpc`) and database `neurosql_db` are ready; a fresh table listing returned no application tables. The separate temporary branch `neurosql-auth-owner-invariant` contains the four proposed `neurosql_app` tables and owner-invariant function for migration testing. No SQL writes or schema changes were performed in this run.
- [x] Render service `neurosql-api` is in the confirmed Dilip Kumar workspace, on the free plan in Singapore, linked to `plDilipkumar/NexusOps` branch `main`, with auto-deploy configured. The latest listed live deployment remains commit `746d58da4d47b1fb49a6796dda8b424c8a6c969d`; no backend deployment was triggered for this unmerged feature branch.
- [ ] The lifecycle service tests use a mocked repository. JDBC statements have not yet been integration-tested against the temporary Neon schema branch; add an isolated PostgreSQL integration test before wiring the service to HTTP authentication.
- [ ] Draft PR #7 still contains the proposed durable-auth/workspace schema. It has not been merged or applied to Neon `main`; explicit approval is required before that production schema change.
- [ ] Authentication remains disabled in production. Registration, account-status management, workspace authorization, and a least-privileged runtime database role are not complete.
- [ ] Render's earlier missed auto-deploy remains unresolved; this run did not modify `main` or trigger a backend deployment.
- [x] No credentials, paid resources, production environment changes, or production database modifications were introduced.
