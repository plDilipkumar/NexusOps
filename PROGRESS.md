# NeuroSQL Progress

Last verified: 2026-10-27

## Verified
- [x] Repository scaffold, README, safety principles, and progress tracker exist in `plDilipkumar/NexusOps`.
- [x] Neon PostgreSQL 17 project `neurosql` is on the free organization in Singapore.
- [x] Read-only SQL checks confirm database `neurosql_db`, PostgreSQL 17.11, and zero public application tables. No schema changes have been made.
- [x] Initial React + TypeScript + Vite workspace UI and Spring Boot 3 / Java 21 API exist.
- [x] Vercel production deployment for the merged backend-health implementation reports `READY`.
- [x] Render free service `neurosql-api` is live and runs the latest backend code from commit `d6609c8`.
- [x] Render Docker build completed `mvn -B verify` successfully before packaging the backend image.
- [x] Render environment variables point to the dedicated Neon database; credentials are not stored in GitHub.
- [x] Merged PR #1 adding `GET /api/health/database`, success/failure unit tests, and test execution in the Docker build.
- [x] GitHub Actions passed backend Maven verification and frontend production build.
- [x] Main-push production smoke tests passed: the deployed endpoint returned `status=UP`, database `neurosql_db`, and PostgreSQL `17.11`.
- [x] Merged PR #2 (commit `93f7507`): the frontend polls `/api/health/database` on load and every 60 seconds, showing the live connection state and database version.
- [x] Added explicit CORS allow-list entries for `https://neurosql.vercel.app` and local Vite development origin.
- [x] GitHub Actions run #10 passed backend Maven verification, frontend production build, and the production API/database smoke test.
- [x] Vercel production deployment for commit `93f7507` reports `READY` on `https://neurosql.vercel.app`.
- [x] Render deployment for commit `93f7507` is live; its logs show Spring Boot started and connected to the dedicated Neon datasource.

## In progress
- [ ] Directly verify browser-side CORS and the rendered live connection indicator. The production bundle is deployed and contains the health poll; CI verified the API/database path, but this run could not execute an actual browser session against the page.
- [ ] Fix or investigate Render auto-deploy integration. A merge to `main` did not create a Render deployment during the observation window, so the live service was deployed manually. The service still reports auto-deploy enabled; do not assume future pushes deploy reliably.

## Next milestones
- [ ] Define reviewed schema for saved connections, query history, and audit events. Prepare/test migrations on a temporary Neon branch and request approval before any main-branch schema change.
- [ ] Add backend schema browsing, safe query validation, query history, and EXPLAIN endpoints.
- [ ] Add authentication/authorization.

## Constraints
- Keep within free-tier usage; no paid resources.
- Never reuse another project's database.
- Never run destructive SQL automatically.
- Do not claim a feature or deployment is complete until verified.
- Read this file before each work session and avoid repeating completed work.
