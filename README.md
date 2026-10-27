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
- `POST /api/sql/validate` accepts a JSON body such as `{"sql":"SELECT 1"}` and returns conservative lexical guardrail results. It never executes the submitted SQL. It is disabled by default and requires `NEUROSQL_SQL_VALIDATOR_ENABLED=true` plus owner authentication.
- The SQL validator is not a PostgreSQL parser or a security boundary; keywords and known side-effecting functions are screened conservatively. It can reject legitimate identifiers and cannot prove that a query is safe. Do not add query execution until a least-privileged read-only database role and database-side read-only transaction enforcement are implemented.
- The initial auth boundary supports one environment-configured owner account over HTTP Basic on HTTPS. Configure `NEUROSQL_AUTH_USERNAME` and `NEUROSQL_AUTH_PASSWORD` only in the deployment environment. When auth is enabled but either credential is missing, the backend fails at startup rather than silently opening access.
- CORS is restricted to the production frontend origin (`https://neurosql.vercel.app`) and local Vite origin (`http://localhost:5173`). The POST method exists only for the disabled-by-default validator.
- The schema explorer UI uses `VITE_SCHEMA_LOGIN_ENABLED=true` to show the owner sign-in form. Leave it unset/false in shared production until the backend's `NEUROSQL_AUTH_ENABLED=true`, `NEUROSQL_SCHEMA_BROWSER_ENABLED=true`, and owner credentials are deliberately configured in Render. The frontend keeps credentials in memory only and sends them via HTTPS Basic auth; it does not write them to localStorage.
- Copy `frontend/.env.example` to `frontend/.env.local` for local settings. Do not commit real credentials.

This is a single-owner security foundation, not yet multi-user authentication or workspace isolation. Keep auth, schema browsing, and SQL validation disabled in shared production until the access model and direct browser verification are complete.

## Safety
- Treat generated SQL as untrusted until reviewed.
- Never execute a draft merely because lexical validation passed.
- Query execution should use a least-privileged read-only database role and a read-only transaction.
- Destructive statements require explicit confirmation.
- Secrets must stay server-side.

See [PROGRESS.md](./PROGRESS.md) for verified progress and remaining work.
