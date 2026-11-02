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
- `POST /api/sql/execute` is a separate, disabled-by-default endpoint. It requires `NEUROSQL_SQL_EXECUTION_ENABLED=true` and either configured owner authentication or durable-session authentication. It accepts only one lexical `SELECT` or `WITH` draft, verifies that the JDBC session is a non-owning LOGIN member of `neurosql_sql_readonly`, rejects database/schema/table/sequence write privileges and unexpected role memberships, then executes inside an explicit PostgreSQL read-only transaction with a statement timeout and bounded result rows/cell values. Responses are `no-store`; binary/large-object cells are omitted.
- The SQL validator is not a PostgreSQL parser or a complete security boundary; it can reject legitimate identifiers and cannot prove that a query is safe. The database role and read-only transaction are mandatory independent controls. The executor is not production-ready until the dedicated Neon runtime role is provisioned and independently verified. The current frontend does not call `/api/sql/execute`; the editor remains a draft surface until its authenticated result grid and query-state handling are implemented. The backend HTTP contract is covered by MockMvc tests for authentication, no-store responses, rejected SQL, unavailable-role responses, and sanitized execution failures.
- Never enable SQL execution with the database owner, a superuser, or the shared application's general-purpose database role. Configure the restricted role name through `NEUROSQL_SQL_EXECUTION_EXPECTED_ROLE` and use a separate runtime database login whose only inherited membership is that read-only group. The executor rechecks role privileges before every query.
- The default execution limits are 500 rows and five seconds. They can be reduced or raised within enforced bounds using `NEUROSQL_SQL_EXECUTION_MAX_ROWS` and `NEUROSQL_SQL_EXECUTION_TIMEOUT_SECONDS`.
- The initial auth boundary supports one environment-configured owner account over HTTP Basic on HTTPS. Configure `NEUROSQL_AUTH_USERNAME` and `NEUROSQL_AUTH_PASSWORD` only in the deployment environment. When auth is enabled but either credential is missing, the backend fails at startup rather than silently opening access.
- CORS is restricted to the production frontend origin (`https://neurosql.vercel.app`) and local Vite origin (`http://localhost:5173`). The POST method exists only for the disabled-by-default validator and executor.
- The schema explorer UI uses `VITE_SCHEMA_LOGIN_ENABLED=true` to show the owner sign-in form. Leave it unset/false in shared production until the backend's `NEUROSQL_AUTH_ENABLED=true`, `NEUROSQL_SCHEMA_BROWSER_ENABLED=true`, and owner credentials are deliberately configured in Render. The frontend keeps credentials in memory only and sends them via HTTPS Basic auth; it does not write them to localStorage.
- Copy `frontend/.env.example` to `frontend/.env.local` for local settings. Do not commit real credentials.

This is a single-owner security foundation plus gated workspace authentication groundwork, not yet production-enabled multi-user SQL execution or workspace isolation. Keep auth, schema browsing, SQL validation, and SQL execution disabled in shared production until the access model, dedicated database roles, and direct browser verification are complete.

## Safety
- Treat generated SQL as untrusted until reviewed.
- Never execute a draft merely because lexical validation passed.
- Query execution uses a least-privileged read-only database role and a read-only transaction; both are checked before every request.
- Result sets are bounded and cached responses are prohibited.
- Destructive statements are rejected; there is no arbitrary write mode.
- Secrets must stay server-side.

See [PROGRESS.md](./PROGRESS.md) for verified progress and remaining work.
