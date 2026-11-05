# NeuroSQL — release progress

Last verified: 2026-11-04 13:49 UTC. Timestamps below use UTC. Earlier detailed run notes remain in Git history; this file is kept concise to avoid stale duplicated status sections.

## Current release state

- Repository: [plDilipkumar/NexusOps](https://github.com/plDilipkumar/NexusOps)
- Current application commit: `2d20070500ae8cc4b194eabfe0f3fb99915b8f97` — PR #13, fail-closed durable-auth database identity guard.
- Latest tracker commit before this run: `e647201d137d40d0138395fd853bd11e7cbb3625`; PR #13 merge commit `2d20070500ae8cc4b194eabfe0f3fb99915b8f97` remains the latest application-code commit.
- Open PRs: 0.
- Last verified main CI: run #486 (`41824995526`), all five jobs passed. Backend 391 tests passed with 0 failures/errors and 71 expected skips; PostgreSQL 17 integration 71/71; frontend test/build/browser checks passed; schema V1–V8 contract passed. The production smoke job was rerun after the latest Render deployment as job `126584307055` and passed.
- Current backend deployment: Render `dep-dblimat9fdbs73dhslvg`, LIVE for tracker commit `1912545c4d2e48853d0f7be3fcc0389e5680d601`, containing application commit `2d20070500ae8cc4b194eabfe0f3fb99915b8f97`.
- Latest frontend deployment: Vercel `dpl_5YRFw9Gu6ARvh4GtLMXbu4oiJDRn`, READY for tracker commit `e647201d137d40d0138395fd853bd11e7cbb3625`; verified alias `neurosql.vercel.app`.
- Production database: Neon project `neurosql` (`lingering-dew-88806235`), main branch `br-empty-credit-b360dmpc`, database `neurosql_db`, PostgreSQL 17.11, Free plan.

## Latest application milestone — personal workspace bootstrap

PR #12, [Create a personal workspace during account registration](https://github.com/plDilipkumar/NexusOps/pull/12), was merged by squash after all four PR checks passed. PR head `480f4158fdbef2802b911d62a2fe70f26d21a224`; merge commit `187f4802bb6c0979d99904860e1407d22d80fcf0`.

Registration creates the unverified account, unique personal workspace, active OWNER membership, and verification-token digest in one transaction. Failure at any stage rolls the transaction back. Proposed migration V8 adds only column-scoped INSERT grants for this bootstrap; it grants no ownership/membership updates, deletes, or schema CREATE.

### CI test counts and checks

- Backend: 388 tests, 0 failures, 0 errors; 71 expected database/external-service skips.
- PostgreSQL 17 integration: 71/71 passed, including registration workspace creation and rollback.
- Frontend Node tests: 96/96 passed.
- Vite production build: 1,596 modules transformed.
- Schema contract: V1–V8 applied and verified on disposable PostgreSQL 17; V8 workspace bootstrap and deferred active-owner invariant passed.
- Docker production image build and non-root runtime assertion passed; expected UID/GID is `10001:10001`.
- Browser checks: responsive navigation at 1440, 851, 850, 390, and 320 px; keyboard/skip-link checks; mocked registration, verification, password reset, durable login, identity, workspace switching, logout, expiry and provisional-session revocation.
- All 12 in-flight SQL/EXPLAIN race combinations remain green: sign-out/session expiry × late success/HTTP error/network error.
- Browser auth flows use a mock API. They do not establish live production authentication.

### Latest main CI job verification

Run #483 jobs were fetched individually; all five jobs completed successfully:
- Frontend production build: job `126535662729`.
- Backend tests and production Docker/non-root assertion: job `126535663507`.
- Production API/database smoke: job `126535674767`.
- Proposed auth schema and runtime grants (ephemeral PostgreSQL 17): job `126535717569`.
- Durable-auth/read-only SQL integration (PostgreSQL 17): job `126535722753`.

The post-deployment smoke job completed at `2026-11-04T11:06:20.485Z` and returned `status=UP`, database `neurosql_db`, PostgreSQL `17.11 (9045acd)`. Render request-log lookup for `/api/health/database` returned no request entries in the 11:00–11:45 UTC window, so the GitHub smoke job remains the direct post-deploy health evidence; no separate live HTTP probe was possible from this runner.

## Temporary Neon migration and least-privilege proof

Temporary branch `br-shy-star-b31qx5c6` (`neurosql-v7-role-validation-20261104`) contains V1–V8 and the V7 read-only group role. Fresh read-only checks confirm:
- V8 column-level workspace and owner-membership INSERT privileges are present for `neurosql_auth_runtime`.
- Auth runtime cannot UPDATE membership role or CREATE in `neurosql_app`.
- `neurosql_sql_readonly` can CONNECT but cannot SELECT from `neurosql_app.app_users`.
- Both temporary probe LOGIN principals now have `rolcanlogin=false` and no membership in either runtime group. The Neon role-list display still labels their authentication method as password; PostgreSQL `pg_roles` readback confirms login is disabled.

The temporary probe verified allowed SELECT access to the explicitly granted probe table and denied writes, DDL, authentication-schema reads, and `SET ROLE`. No production main changes were made during this audit.

## Production deployment verification

### Render backend

- Service: `neurosql-api`, ID `srv-dbgb6vt9fdbs73da93n0`, Free plan, Singapore, branch `main`, auto-deploy setting `yes`.
- Current deployment: `dep-dblimat9fdbs73dhslvg`, LIVE for tracker commit `1912545c4d2e48853d0f7be3fcc0389e5680d601` (application commit `2d200705`); build succeeded at `2026-11-04T12:55:29.647Z`, deployment became LIVE at `2026-11-04T12:56:52.554Z`.
- Latest post-deploy GitHub smoke job `126587072484` passed at `2026-11-04T12:57:21Z`, returning `status=UP`, database `neurosql_db`, PostgreSQL `17.11 (9045acd)`.
- Application error-log query for 11:00–11:45 UTC returned no error entries.
- Render environment-variable readback is not available through the connected interface. The last configuration write set the non-secret feature gates false; current overrides cannot be independently re-read.

### Vercel frontend

- Project: `neurosql`, ID `prj_bWhKWqbbb5EaNRdez05bEsMPe35a`.
- Latest deployment `dpl_5YRFw9Gu6ARvh4GtLMXbu4oiJDRn`: READY, production target, aliases include `neurosql.vercel.app`, Git commit `e647201d137d40d0138395fd853bd11e7cbb3625`.
- Custom domain `neurosql.vercel.app` is verified. SSO protection is configured for all deployment domains except custom domains.
- Project metadata still reports `live=false` despite the READY production deployment and verified alias; treat this as a metadata discrepancy, not a failed deployment.
- Production environment inventory: seven visible non-secret plain variables and zero hidden variables. `VITE_API_BASE_URL` explicitly targets the Render API; all six frontend feature gates are explicitly `false`.

## Neon production main — unchanged

- Project plan: `free_v3`; AWS Singapore; PostgreSQL 17; default compute 0.25 CU. Seven branches are listed against the project's ten-branch limit; no branch was created during this run.
- Eight V1–V6 tables exist and are empty: `app_users`, `workspaces`, `workspace_memberships`, `user_sessions`, `auth_login_rate_limits`, `account_action_tokens`, `query_history`, and `saved_queries`.
- Production roles: `neurosql_owner` (LOGIN, CREATEDB, CREATEROLE, REPLICATION, BYPASSRLS) and `neurosql_auth_runtime` (NOLOGIN, no elevated attributes). The owner currently has a non-inheriting, non-SET, ADMIN-option membership in the auth group; it is not a runtime login and must not be used as one.
- Read-only SQL confirms V8 workspace/membership INSERT privileges are still false on production main. `neurosql_sql_readonly` does not exist on production main.
- No production V7/V8 migration, production LOGIN role, or role membership change was made. Earlier explicit approval covered V1–V6 only.

## Release blockers — keep features disabled

1. A dedicated production authentication LOGIN principal inheriting `neurosql_auth_runtime` with `INHERIT TRUE, SET FALSE, ADMIN FALSE` requires explicit approval and a new credential.
2. Production V7 and V8, plus a separate SQL-read-only LOGIN principal with explicit SELECT grants on approved user-data schemas/tables, require explicit approval. SQL execution and EXPLAIN remain disabled.
3. SMTP credentials and a verified sender/domain are missing; email verification and password-reset delivery are not operational.
4. Gemini/provider key presence cannot be independently verified through Render environment readback; no live provider request was made. All AI features remain disabled.
5. Production login, email delivery, workspace isolation, SQL execution/history, and live AI behavior are not end-to-end verified. Mock browser tests are not a substitute for live auth.
6. Vercel production now has an explicit non-secret API base URL and six false feature flags; no production secrets are stored in Vercel.

## Recent security milestones

- PR #9 merged as `b9eb8be029f5f16ae9688234f90dc2b24a2eb683`: session-bound SQL cancellation, EXPLAIN invalidation and session-expiry race hardening.
- PR #10 merged as `3d4d8cf52ab81311a5263ea4f407fa9b790755d4`: non-delegable inherited-only PostgreSQL role memberships.
- PR #11 merged as `9cd10ee84cfe3d7a661bd21dbcecdf057accb294`: Render container runs as UID/GID 10001.
- PR #12 merged as `187f4802bb6c0979d99904860e1407d22d80fcf0`: transactional personal workspace and OWNER membership bootstrap.

## Run 7 audit — 2026-11-04 11:45 UTC

- Re-read this tracker and inspected main commits, open/recent PRs, main CI jobs, Neon project/main/temporary branch, Vercel project/deployment/domain/env inventory, and Render service/deployment/events/logs.
- No new application commit or open PR appeared after PR #12. No code changes were made in this audit.
- Fresh production role/table/privilege readback confirmed all eight tables remain empty; production V8 grants and V7 SQL role remain absent.
- Fresh temporary-branch checks reconfirmed V7/V8 least-privilege behavior and that the two probe principals are NOLOGIN with no runtime-group memberships.
- The Vercel tracker deployment `dpl_33XUTjBFqq6hPt91huLUfNBB3tHT` is READY and the custom domain is verified. Render deployment `dep-dblh208u01pc73a6onbg` remains LIVE. No error-level application logs were returned for the audit window.
- No paid resources, destructive operations, cross-project database reuse, source-controlled credentials, or unapproved production schema/role changes were introduced.


## Run 7 continuation — PR #13 durable-auth database identity guard

PR #13, [Fail closed when durable auth uses an unsafe database identity](https://github.com/plDilipkumar/NexusOps/pull/13), was merged by squash after all four PR checks passed.

- PR head: `3935b3b0ac8061e4c78223e04e2e2efa214c6fad`.
- Merge commit: `2d20070500ae8cc4b194eabfe0f3fb99915b8f97`.
- PR CI run #485: `41824714562`.
- Backend job `126556395196`: 391 tests, 0 failures, 0 errors, 71 expected skips; includes 3 new guard unit tests. Production Docker build and UID/GID 10001 assertion passed.
- PostgreSQL 17 integration job `126556395295`: 71/71 passed.
- Auth schema/role contract job `126556395317`: passed, including the new disposable LOGIN membership contract.
- Frontend job `126556394774`: 96/96 unit tests, production build, responsive and mocked account/session browser tests passed.
- Production smoke was skipped for the PR event; the last post-deploy smoke remains job `126535674767` for the previous Render deployment.

The new `DurableAuthDatabaseRoleGuard` runs only when durable sessions are enabled and outside the disposable `test` profile. It requires the datasource's `session_user` to be a non-privileged LOGIN with exactly one membership in `neurosql_auth_runtime`, using inherited privileges but no SET ROLE or ADMIN OPTION, and no schema CREATE. It refuses startup if the datasource uses the elevated database owner. The first CI attempt correctly exposed that existing Spring integration tests used the admin login; the tests were moved to the explicit `test` profile, then the full PR run #485 passed. Unit tests cover accept/reject/missing-role cases, while the disposable PostgreSQL contract verifies the actual PG17 membership options.

### Post-merge deployment state

- Vercel deployment `dpl_4f8THEVF6NHk3shy3RSDHdKD6WwT` is READY for merge commit `2d200705`, with aliases including `neurosql.vercel.app`.
- Render service still reports auto-deploy enabled on `main`, but no deployment for `2d200705` appeared in the deploy list after the merge and a two-minute observation window. The current LIVE backend remains deployment `dep-dblh208u01pc73a6onbg` for commit `187f4802`. No manual deploy was triggered because Render's service is configured for automatic commit deploys; the latest backend commit therefore still needs live deployment verification.
- GitHub combined commit status reports Vercel success. The connected workflow-run listing only exposes pull-request-triggered runs, so no claim is made that the post-merge push CI or production smoke completed.

No Neon production schema or roles were changed. The durable-auth guard is conditional and has not been activated in production. Production auth/SQL/AI/email gates remain disabled pending dedicated LOGIN role approval, V7/V8 production approval, SMTP credentials, and verifiable Gemini/provider credentials.


## Run 8 audit — 2026-11-04 12:52 UTC

- Re-read this tracker and inspected the repository/default branch, open PRs, main CI run #486, Neon project/main/temporary branch, Vercel project and production deployments, and Render service/deployments/events/logs.
- Repository main application commit remains PR #13 merge `2d20070500ae8cc4b194eabfe0f3fb99915b8f97`; no open PRs.
- Main CI run #486 (`41824995526`) passed all five jobs. Backend: 391 tests, 0 failures, 0 errors, 71 expected database/external-service skips. PostgreSQL 17 integration: 71/71. Frontend production build and unit/browser checks passed. Responsive navigation passed at 1440, 851, 850, 390, and 320 px; account/session flows use a mock API. The 12 in-flight SQL/EXPLAIN sign-out/session-expiry race combinations remain covered by the passing browser suite. V1–V8 schema and least-privilege contracts passed on disposable PostgreSQL 17.
- Render did not auto-deploy the PR #13 merge despite autoDeploy being enabled. After confirming CI success and no deployment for the merged code, a deployment was triggered: `dep-dblijeou01pc73aan030`, source commit `687f2f9a1e97b72cac5c81bd21e4ea17a5322c73` (tracker-only commit containing app commit `2d200705`). Build succeeded at `2026-11-04T12:50:01Z`; Spring Boot started at `12:51:19Z`; Render emitted `Your service is live` and `deploy_ended/succeeded` at `12:51:24Z`. Runtime log shows Java 21.0.13 and process started by an unprivileged user marker rather than root; CI's production Docker UID/GID assertion remains green.
- Targeted post-deployment rerun of GitHub smoke job `126584307055` passed at `2026-11-04T12:51:43Z`, returning `status=UP`, database `neurosql_db`, PostgreSQL `17.11 (9045acd)`. This is post-deployment health evidence for the latest Render release.
- Vercel production deployment `dpl_DBJFP81q4hgKvYfn9nG6XXXqFapy` is READY, aliases include verified custom domain `neurosql.vercel.app`, source commit `687f2f9a1e97b72cac5c81bd21e4ea17a5322c73`. Project metadata still says `live=false` despite READY state and alias; treat as a metadata discrepancy.
- Production Neon main (`br-empty-credit-b360dmpc`) was read-only inspected. All eight V1–V6 tables remain empty. `neurosql_owner` remains the only LOGIN and has elevated CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS attributes; `neurosql_auth_runtime` is NOLOGIN. Production V7/V8 grants are absent and `neurosql_sql_readonly` does not exist on main.
- Temporary Neon branch `br-shy-star-b31qx5c6` was read-only rechecked. V7 group role and V8 column-scoped bootstrap grants are present. Auth runtime can INSERT only the named workspace and membership bootstrap columns; it cannot UPDATE membership role. Temporary auth/SQL probe roles have `rolcanlogin=false` and no remaining runtime-group membership. The earlier V7 probe still confirms no SQL write/DDL access and no access to `neurosql_app.app_users` through the read-only role.
- No production schema or role changes were made. V1–V6 remain the only approved production migrations. V7/V8 and production LOGIN identities remain gated.
- Release blockers remain: explicit approval for a dedicated production auth LOGIN and production V7/V8 plus a separate SQL-read-only LOGIN; SMTP credentials and verified sender; independently verifiable Gemini key and a live provider test. Production auth/workspace isolation, SQL execution/history and AI remain disabled pending these gates. Render environment readback is unavailable; Vercel production env inventory remains empty and frontend uses the source API URL fallback.
- No paid resources, destructive operations, cross-project database reuse, or source-controlled credentials were introduced.

### Additional production role-membership audit — 2026-11-04 12:54 UTC

- Read-only `pg_auth_members` inspection on Neon main confirms `neurosql_owner` is a member of Neon-managed `neon_superuser` with `INHERIT TRUE, SET TRUE`, and a member of `neurosql_auth_runtime` with `INHERIT FALSE, SET FALSE, ADMIN TRUE`. Combined with CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS, this login is categorically unsuitable for application runtime use. The durable-auth startup guard correctly rejects it. No production role membership or attribute was changed.
- Temporary-branch probe LOGIN roles remain `rolcanlogin=false`; they have no remaining memberships in `neurosql_auth_runtime` or `neurosql_sql_readonly`.

### Run 8 continuation — Render feature-gate reassertion and post-deploy smoke — 2026-11-04 12:57 UTC

- Re-applied 12 non-secret Render settings using merge mode (`replace=false`), preserving existing database/provider/SMTP credentials: `NEUROSQL_SCHEMA_BROWSER_ENABLED=false`, `NEUROSQL_SQL_VALIDATOR_ENABLED=false`, `NEUROSQL_AUTH_ENABLED=false`, `NEUROSQL_AUTH_DURABLE_SESSIONS_ENABLED=false`, `NEUROSQL_AUTH_SHARED_RATE_LIMIT_ENABLED=false`, `NEUROSQL_AUTH_EMAIL_ENABLED=false`, `NEUROSQL_SQL_EXECUTION_ENABLED=false`, `NEUROSQL_SQL_HISTORY_ENABLED=false`, `NEUROSQL_SAVED_QUERIES_ENABLED=false`, `NEUROSQL_AI_SQL_GENERATION_ENABLED=false`, `NEUROSQL_AI_SQL_PERFORMANCE_ENABLED=false`, and `NEUROSQL_AUTH_EXPECTED_DATABASE=neurosql_db`. Render confirmed the update and triggered deployment `dep-dblimat9fdbs73dhslvg` for tracker commit `1912545c4d2e48853d0f7be3fcc0389e5680d601`.
- Render build succeeded at `2026-11-04T12:55:29Z`; new Java 21.0.13 process started as non-root marker `?`, Tomcat started on port 10000, Spring Boot started at `12:56:49Z`, and deployment became LIVE at `12:56:52Z`.
- Post-env-update live smoke rerun job `126587072484` passed at `2026-11-04T12:57:21Z`, returning `status=UP`, database `neurosql_db`, PostgreSQL `17.11 (9045acd)`. The main workflow run #486 latest attempt has all five jobs successful.
- Vercel production environment inventory was rechecked: zero variables and zero hidden production variables. `frontend/.env.example` uses the Render API URL fallback and all six Vite feature flags default false. Latest tracker deployment `dpl_FWZifvxjCCXfzUnZvuLkQQNpQj5o` is READY for commit `1912545c4d2e48853d0f7be3fcc0389e5680d601`, with `neurosql.vercel.app` assigned.
- Render environment-variable values cannot be read back through the available connector; the update call succeeded but the resulting key/value inventory is not independently readable. No secret values were fetched, printed, replaced, or added. AI/email/auth/SQL remain disabled.


## Run 9 audit — 2026-11-04 13:49 UTC

- Re-read this tracker and inspected the repository main history, recent/open PRs, latest GitHub Actions run, Neon project/main/temporary branch, Vercel project/deployments/environment inventory, and Render service/deployment/events/logs.
- Main application commit remains PR #13 merge `2d20070500ae8cc4b194eabfe0f3fb99915b8f97`. Latest main commit before this tracker update is `e647201d137d40d0138395fd853bd11e7cbb3625`, a PROGRESS.md-only commit relative to the application commit. There are zero open PRs; PR #13 remains the latest merged PR.
- Main CI run #486 (`41824995526`, attempt 3) remains green: all five jobs succeeded. Backend 391 passed, 0 failures/errors, 71 expected database/external-service skips; PostgreSQL 17 integration 71/71; frontend 96/96 unit tests and browser checks passed; production Vite build transformed 1,596 modules; all 12 sign-out/session-expiry × SQL/EXPLAIN in-flight race combinations remain green; V1–V8 schema/role contract passed. No new code commit was made, so no new application CI run was required.
- Vercel production configuration was made explicit without adding secrets. Added seven production-only plain variables: `VITE_API_BASE_URL=https://neurosql-api.onrender.com`; `VITE_SCHEMA_LOGIN_ENABLED=false`; `VITE_DURABLE_AUTH_ENABLED=false`; `VITE_SQL_EXECUTION_ENABLED=false`; `VITE_AI_SQL_GENERATION_ENABLED=false`; `VITE_AI_SQL_EXPLANATION_ENABLED=false`; and `VITE_AI_SQL_PERFORMANCE_ENABLED=false`. A no-decrypt inventory confirms seven visible variables, zero hidden production variables, and no reported security issues. No API keys or other credentials were placed in Vercel.
- Redeployed the existing production frontend after the environment update. Vercel deployment `dpl_5YRFw9Gu6ARvh4GtLMXbu4oiJDRn` is READY for main commit `e647201d137d40d0138395fd853bd11e7cbb3625`; Vite transformed 1,596 modules and completed the build. The verified `neurosql.vercel.app` alias is assigned. Build emitted non-fatal npm warnings about an unknown `cache-dir` config and an `esbuild` install-script allowlist; the build still completed successfully. Project metadata still reports `live=false` despite READY production deployment and alias.
- Render service `srv-dbgb6vt9fdbs73da93n0` remains on the Free plan in Singapore, tracking `main` with auto-deploy enabled. Current LIVE deployment is `dep-dblimat9fdbs73dhslvg` for tracker commit `1912545c4d2e48853d0f7be3fcc0389e5680d601`, containing application commit `2d200705`. Post-deployment GitHub smoke job `126587072484` passed at `2026-11-04T12:57:21Z`, returning `status=UP`, database `neurosql_db`, PostgreSQL `17.11 (9045acd)`. Error-level app log query after deployment returned no entries. Render request-log query for `/api/health/database` after 12:57 UTC returned no entries, so the GitHub smoke job is the direct post-deploy health evidence for this run.
- Operational gap: Render's service `healthCheckPath` is currently blank. The connected Render actions expose service reads/deploys/environment updates but no service-settings update, so I did not guess at an API or change service configuration. A Render dashboard health check at `/api/health` can be configured separately; that endpoint reports process health, while `/api/health/database` is the DB-aware smoke endpoint.
- Neon project `neurosql` remains Free v3, AWS Singapore, PostgreSQL 17.11, 0.25 CU; seven branches are listed against the ten-branch limit. Production main `br-empty-credit-b360dmpc` remains unchanged. Read-only count confirms all eight application tables are empty. `neurosql_owner` remains elevated (CREATEDB, CREATEROLE, REPLICATION, BYPASSRLS) and inherits Neon-managed `neon_superuser`; it also has an unsafe ADMIN-option membership in `neurosql_auth_runtime`. `neurosql_auth_runtime` is NOLOGIN; V7 role and V8 workspace bootstrap grants remain absent on main.
- Temporary branch `br-shy-star-b31qx5c6` still contains V1–V8 and V7. Read-only checks reconfirm V8 workspace-name INSERT is allowed, workspace ownership and membership-role UPDATE are denied, `neurosql_sql_readonly` can CONNECT but has no USAGE on `neurosql_app` and no SELECT on `app_users`. Both temporary probe principals have `rolcanlogin=false`, no elevated attributes, and no remaining group memberships.
- Production blockers remain: explicit approval for V7/V8 and separate credential-bearing auth/SQL LOGIN principals; SMTP credentials and verified sender; independently verifiable Gemini/provider key and live provider test. Durable authentication, email, SQL execution/history, and AI remain disabled. Render environment values cannot be read back through the connector, so provider-key presence is not claimed.
- No production Neon schema/role changes, destructive operations, paid resources, cross-project database reuse, or source-controlled credentials were introduced.


## Run 10 — approved production database migration and identity provisioning — 2026-11-05

User explicitly approved V7/V8 on Neon main and creation of dedicated least-privilege production LOGIN identities.

### Repository and CI inspection
- Re-read this file at start of run. GitHub default branch is `main`; latest recorded application commit remains `2d20070500ae8cc4b194eabfe0f3fb99915b8f97`.
- Open PRs: 0.
- Latest CI run #486 (`41824995526`) remains completed/successful with all five jobs green. Backend 391 passed, 0 failures/errors, 71 expected skips; PostgreSQL 17 integration 71/71; frontend 96/96 plus production build/browser checks; V1–V8 contract passed; 12 SQL/EXPLAIN sign-out/session-expiry race combinations passed.
- No new application-code changes were made in this run.

### Neon production migration
- Project `neurosql`, ID `lingering-dew-88806235`; default/main branch `br-empty-credit-b360dmpc`; database `neurosql_db`; PostgreSQL 17.
- Prepared approved V7+V8 migration on temporary branch `br-proud-hall-b3zb7j32`, migration ID `4e5b8632-857c-4860-bb7d-11a6a46fdcf5`.
- Temporary branch verification confirmed V7 group role is NOLOGIN, NOINHERIT, NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOREPLICATION, NOBYPASSRLS; CONNECT allowed, database CREATE denied, and no USAGE/SELECT on `neurosql_app`. V8 column-scoped INSERT grants for workspace and owner membership were present. Existing V2 column-scoped auth SELECT grants were also verified. Temporary branch had no customer data schema beyond `neurosql_app` and `public`.
- After verification, applied the exact prepared V7+V8 SQL to Neon main using the user's explicit approval. Neon confirmed successful application and deletion of temporary branch `br-proud-hall-b3zb7j32`.
- Created production LOGIN `neurosql_auth_login` with INHERIT TRUE, no superuser/createdb/createrole/replication/BYPASSRLS; granted exactly `neurosql_auth_runtime` with INHERIT TRUE, SET FALSE, ADMIN FALSE.
- Created production LOGIN `neurosql_sql_login` with the same non-privileged attributes; granted exactly `neurosql_sql_readonly` with INHERIT TRUE, SET FALSE, ADMIN FALSE.
- Readback confirmed both LOGIN roles have exactly one intended group membership and no elevated role attributes. Passwords have NOT been generated; neither login is yet configured in Render.
- Auth runtime privileges verified on main: schema USAGE true, CREATE false; email/session hash column SELECT true; display_name SELECT false; workspace and membership bootstrap column INSERT true.
- SQL login can CONNECT, cannot CREATE in database, has no USAGE or SELECT on `neurosql_app`. There are currently no customer data tables outside `neurosql_app`; public schema exists but contains no application tables. SQL execution must remain disabled until the intended user-data schema/table grants are explicitly established and tested.
- Production role inventory now includes owner, auth group, SQL group, auth LOGIN, SQL LOGIN. The elevated `neurosql_owner` remains unsuitable for application runtime and is not used as an application datasource.

### Render / Vercel inspection and blockers
- Render workspace listing returned one workspace: `Dilip kumar's workspace`, ID `tea-d2amubadbo4c73a1jvag`. Per workspace-selection safety, no Render service/env/deployment action has been performed in this run pending user confirmation of that workspace.
- Vercel project `neurosql`, ID `prj_bWhKWqbbb5EaNRdez05bEsMPe35a`; latest production deployment `dpl_AEuePiqQAs4FSVvQU14fvaaVXc4F` READY, custom domain `neurosql.vercel.app`. Seven non-secret production variables are recorded from the previous run; no secrets are stored in Vercel.
- Gemini key was reported by user as already present in Render, but provider key presence and a live Gemini request are not independently verified. AI remains disabled.
- SMTP credentials and verified sender are still missing. Email verification/password reset remain disabled. Recommended no-cost candidate: Brevo Free, official docs state 300 emails/day and transactional SMTP support.
- Production auth, SQL execution, and AI remain disabled until Render JDBC credentials and app flags are configured, auth is tested live, SQL data-plane grants are designed for actual customer data schemas, and Gemini provider test succeeds.
- No destructive or paid operations, cross-project DB use, or source-controlled credentials were introduced.
