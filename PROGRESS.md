# NeuroSQL Progress

Last verified: 2026-10-27

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Neon PostgreSQL 17 project `neurosql` is on the free organization in Singapore.
- [x] Read-only SQL checks confirm database `neurosql_db`, PostgreSQL 17.11, and zero application tables. No schema changes have been made.
- [x] Initial React + TypeScript + Vite workspace UI and Spring Boot 3 / Java 21 API exist.
- [x] Vercel project `neurosql` remains linked to this repository; the latest production deployment reports `READY`.
- [x] Render free service `neurosql-api` is live on commit `3a35086` and uses the dedicated Neon datasource. This deployment had to be triggered manually because a merge to `main` did not automatically start a deploy.
- [x] Render Docker build runs `mvn -B verify` before packaging the backend image.
- [x] Render environment variables point to the dedicated Neon database; credentials are not stored in GitHub.
- [x] `GET /api/health/database` verifies live connectivity and reports database `neurosql_db` and PostgreSQL `17.11` without exposing credentials.
- [x] The frontend polls database health on load and every 60 seconds, with explicit connected/unavailable states.
- [x] Read-only `GET /api/schema` reads PostgreSQL information-schema metadata and groups schemas, tables, and ordered column metadata. It performs no writes.
- [x] Schema endpoint remains disabled by default; no application tables have been created in Neon.

## In progress
- [ ] New branch `feat/api-auth-boundary` adds environment-configured owner authentication, fail-closed schema authorization, and a restricted CORS allow-list. CI is pending; production flags remain off.
- [ ] Directly verify browser-side CORS and the rendered live connection indicator; no actual browser session has been run.
- [ ] Investigate Render auto-deploy reliability. Auto-deploy is configured for commits to `main`, but a previous merge required manual deployment.

## Next milestones
- [ ] Complete CI verification for the API security boundary and merge only after tests pass.
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
