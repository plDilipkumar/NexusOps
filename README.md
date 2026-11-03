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
- `POST /api/sql/execute` is a separate, disabled-by-default endpoint. It requires `NEUROSQL_SQL_EXECUTION_ENABLED=true` and either configured owner authentication or durable-session authentication. It accepts only one lexical `SELECT` or `WITH` draft, verifies that the JDBC session is a non-owning LOGIN member of `neurosql_sql_readonly`, rejects database/schema/table/sequence write and PostgreSQL 17 MAINTAIN privileges, as well as unexpected role memberships, then executes inside an explicit PostgreSQL read-only transaction with a statement timeout and bounded result rows/cell values. Responses are `no-store`; binary/large-object cells are omitted.
- The SQL validator is not a PostgreSQL parser or a complete security boundary; it can reject legitimate identifiers and cannot prove that a query is safe. The database role and read-only transaction are mandatory independent controls. The executor is not production-ready until the dedicated Neon runtime role is provisioned and independently verified. The frontend SQL editor can call `/api/sql/execute` when `VITE_SQL_EXECUTION_ENABLED=true` and the user has signed in through the enabled owner or durable-session flow. It renders a bounded result grid, row count, execution time, truncation notice, and sanitized API errors. The frontend flag defaults to false; leave it disabled in shared production until the dedicated Neon read-only login and backend execution flag are independently verified. The backend HTTP contract is covered by MockMvc tests for authentication, no-store responses, rejected SQL, unavailable-role responses, and sanitized execution failures.
- Never enable SQL execution with the database owner, a superuser, or the shared application's general-purpose database role. Configure the restricted role name through `NEUROSQL_SQL_EXECUTION_EXPECTED_ROLE` and use a separate runtime database login whose only inherited membership is that read-only group. The executor rechecks role privileges before every query.
- The default execution limits are 500 rows and five seconds. They can be reduced or raised within enforced bounds using `NEUROSQL_SQL_EXECUTION_MAX_ROWS` and `NEUROSQL_SQL_EXECUTION_TIMEOUT_SECONDS`.
- The initial auth boundary supports one environment-configured owner account over HTTP Basic on HTTPS. Configure `NEUROSQL_AUTH_USERNAME` and `NEUROSQL_AUTH_PASSWORD` only in the deployment environment. When auth is enabled but either credential is missing, the backend fails at startup rather than silently opening access.
- CORS is restricted to the production frontend origin (`https://neurosql.vercel.app`) and local Vite origin (`http://localhost:5173`). The POST method exists only for the disabled-by-default validator and executor.
- The SQL editor uses `VITE_SQL_EXECUTION_ENABLED=true` only after the backend execution flag and dedicated read-only database identity have been verified. The flag defaults to false.
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


## AI SQL drafting (disabled by default)
- `POST /api/sql/ai/generate` accepts a natural-language prompt and bounded schema metadata, then returns one SQL draft. It never executes the generated statement. The existing `ReadOnlySqlValidator` rejects non-SELECT/WITH drafts, multiple statements, mutation keywords, selected side-effecting functions, and row-locking clauses; this lexical check is not a parser or a proof of safety.
- The endpoint is created only when `NEUROSQL_AI_SQL_GENERATION_ENABLED=true`, `NEUROSQL_AUTH_DURABLE_SESSIONS_ENABLED=true`, and `NEUROSQL_AUTH_SHARED_RATE_LIMIT_ENABLED=true`. It also requires an active durable session and viewer membership in the workspace supplied through `X-NeuroSQL-Workspace-Id`.
- AI calls are limited to five attempts per authenticated user per rolling 15-minute window using the existing HMAC-keyed PostgreSQL rate-limit table. The limit is shared across application instances and fails closed when that database is unavailable.
- Configure `NEUROSQL_AI_SQL_GENERATION_ENDPOINT` as an operator-controlled HTTPS OpenAI-compatible chat-completions endpoint, with `NEUROSQL_AI_SQL_GENERATION_MODEL` and `NEUROSQL_AI_SQL_GENERATION_API_KEY` supplied only through deployment environment variables. Connect/read timeouts default to three/twenty seconds and are bounded at startup.
- Prompt length is limited to 2,000 code points; schema context is limited to 20 schemas, 40 tables, 25 columns per table, 400 total columns, and 16,000 serialized characters. Only schema/table/column names and data types are sent; row data is not sent.
- Provider errors are redacted. Generated output is returned only after read-only validation and is always an unexecuted draft. The frontend Copilot is not yet connected to this endpoint; no provider key or AI enablement flag has been configured in production.


## AI performance recommendations (disabled by default)
- POST /api/sql/ai/performance accepts one SQL draft and returns advisory recommendations based on a PostgreSQL EXPLAIN (FORMAT JSON, COSTS TRUE, VERBOSE FALSE, SETTINGS FALSE) plan. It never uses EXPLAIN ANALYZE, executes the query, or applies a suggested change.
- The endpoint is created only when NEUROSQL_AI_SQL_PERFORMANCE_ENABLED=true, NEUROSQL_AI_SQL_GENERATION_ENABLED=true, NEUROSQL_SQL_EXECUTION_ENABLED=true, NEUROSQL_AUTH_DURABLE_SESSIONS_ENABLED=true, and NEUROSQL_AUTH_SHARED_RATE_LIMIT_ENABLED=true. It also requires an active durable session and viewer membership in the selected workspace.
- Plan generation uses the separate, dedicated read-only SQL datasource and repeats the existing database-role privilege checks and read-only transaction enforcement. It fails closed until the restricted Neon LOGIN role is configured and verified.
- The AI provider receives only a bounded plan tree containing node types, schema/object identifiers, planner costs, and estimated rows. SQL text, filter expressions, literal values, and result rows are excluded. Large plans are truncated by node/depth limits.
- PostgreSQL planner costs are planner estimates, not milliseconds; Plan Rows are estimates, not actual row counts. Recommendations are advisory and must be checked against representative workload evidence. The endpoint shares the existing five-request rolling quota across AI SQL generation, explanation, and performance recommendations.
- The endpoint remains disabled by default. Configure provider endpoint, model, and API key only through deployment environment variables; never commit credentials.
