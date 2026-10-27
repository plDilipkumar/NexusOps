# NeuroSQL

An AI-assisted SQL workspace for schema exploration, natural-language-to-SQL drafting, query diagnostics, and safer performance tuning.

## Stack
- Frontend: React + TypeScript + Vite
- Backend: Java 21 + Spring Boot 3
- Database: PostgreSQL 17 on Neon
- Deployment target: Vercel (frontend), Render (API)

## Local development
Frontend: `cd frontend && npm install && npm run dev`
Backend: `cd backend && mvn spring-boot:run`

Configure `JDBC_DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` through environment variables. Never commit credentials.

## API notes
- `GET /api/health` reports API availability.
- `GET /api/health/database` verifies connectivity to the configured database.
- `GET /api/schema` is a read-only schema metadata endpoint. It is denied by default and is only reachable when both `NEUROSQL_AUTH_ENABLED=true` and `NEUROSQL_SCHEMA_BROWSER_ENABLED=true`.
- The initial auth boundary supports one environment-configured owner account over HTTP Basic on HTTPS. Configure `NEUROSQL_AUTH_USERNAME` and `NEUROSQL_AUTH_PASSWORD` only in the deployment environment. When auth is enabled but either credential is missing, the backend fails at startup rather than silently opening access.
- CORS is restricted to the production frontend origin (`https://neurosql.vercel.app`) and local Vite origin (`http://localhost:5173`).

This is a single-owner security foundation, not yet multi-user authentication or workspace isolation. Keep both auth/schema flags disabled in shared production until the frontend login flow and direct browser verification are complete.

## Safety
- Treat generated SQL as untrusted until reviewed.
- Query execution should be read-only by default.
- Destructive statements require explicit confirmation.
- Secrets must stay server-side.
See [PROGRESS.md](./PROGRESS.md) for verified progress and remaining work.
