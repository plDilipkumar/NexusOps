# NeuroSQL Progress

Last verified: 2026-10-27

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Neon PostgreSQL 17 project `neurosql` is on the free organization in Singapore.
- [x] Read-only SQL checks confirm database `neurosql_db`, PostgreSQL 17.11, and zero application tables. No schema changes have been made.
- [x] Initial React + TypeScript + Vite workspace UI and Spring Boot 3 / Java 21 API exist.
- [x] Vercel project `neurosql` remains linked to this repository; latest inspected production deployment reports `READY`.
- [x] Render free service `neurosql-api` is live on the dedicated Neon datasource; most recent successful service deploy is for commit `93f7507`.
- [x] Render Docker build runs `mvn -B verify` before packaging the backend image.
- [x] Render environment variables point to the dedicated Neon database; credentials are not stored in GitHub.
- [x] Merged PR #1 added `GET /api/health/database`, health tests, and test execution in the Docker build.
- [x] Main-push production smoke tests passed: the deployed endpoint returned `status=UP`, database `neurosql_db`, and PostgreSQL `17.11`.
- [x] Merged PR #2 (commit `93f7507`): the frontend polls `/api/health/database` on load and every 60 seconds, showing the live connection state and database version.
- [x] Added explicit CORS allow-list entries for `https://neurosql.vercel.app` and the local Vite development origin.
- [x] Added a read-only `GET /api/schema` implementation on PR #3. It reads only PostgreSQL information-schema metadata, groups schemas/tables/columns, preserves column order, and performs no writes.
- [x] Added schema API tests for an empty database and a populated table with ordered nullable/non-nullable columns. GitHub Actions run #12 reports backend `mvn -B verify` completed successfully and the frontend production build passed.
- [x] Schema endpoint is disabled by default via `NEUROSQL_SCHEMA_BROWSER_ENABLED=false` because table/column names can be sensitive before authentication and workspace authorization exist.
- [x] README documents the schema endpoint and its security gate.

## In progress
- [ ] PR #3 still needs its final CI result and merge verification before the schema API code is part of `main`.
- [ ] Directly verify browser-side CORS and the rendered live connection indicator; this run did not execute an actual browser session.
- [ ] Investigate Render auto-deploy reliability. The service reports auto-deploy enabled, but earlier commits required manual deployment; do not assume future pushes deploy reliably.

## Next milestones
- [ ] Implement authentication and workspace authorization before enabling schema metadata in production.
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
