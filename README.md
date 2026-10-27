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
- `GET /api/schema` is a read-only schema metadata endpoint, currently disabled by default. It returns non-system schemas, tables, and column metadata when enabled with `NEUROSQL_SCHEMA_BROWSER_ENABLED=true`. Keep it disabled in shared deployments until authentication and workspace authorization are implemented; schema names can be sensitive.

## Safety
- Treat generated SQL as untrusted until reviewed.
- Query execution should be read-only by default.
- Destructive statements require explicit confirmation.
- Secrets must stay server-side.
See [PROGRESS.md](./PROGRESS.md) for verified progress and remaining work.
