-- PROPOSED ONLY: workspace-scoped query history and saved SQL.
-- Depends on V1__durable_auth_workspaces.sql and V2__durable_auth_runtime_grants.sql,
-- plus V3/V4 for the complete proposed auth schema.
-- This file is documentation/approval material, not an application startup migration.
-- Never apply it to Neon main without explicit user approval.
--
-- Query history stores user-authored SQL text. The API must enforce active workspace
-- membership and user scoping, avoid logging SQL bodies, and provide a retention policy.
-- History is append-only for the runtime role. Saved queries are editable/deletable,
-- but the owner and workspace keys are immutable through runtime grants.

CREATE TABLE neurosql_app.query_history (
    history_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    user_id UUID NOT NULL,
    query_text TEXT NOT NULL
        CHECK (length(query_text) BETWEEN 1 AND 50000 AND btrim(query_text) <> ''),
    query_hash VARCHAR(64) NOT NULL
        CHECK (query_hash ~ '^[0-9a-f]{64}$'),
    outcome VARCHAR(16) NOT NULL
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'CANCELED')),
    row_count INTEGER,
    execution_time_ms INTEGER NOT NULL
        CHECK (execution_time_ms BETWEEN 0 AND 30000),
    truncated BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_query_history_workspace_member
        FOREIGN KEY (workspace_id, user_id)
        REFERENCES neurosql_app.workspace_memberships (workspace_id, user_id)
        ON DELETE CASCADE,
    CONSTRAINT query_history_outcome_consistency CHECK (
        (outcome = 'SUCCEEDED' AND row_count IS NOT NULL AND row_count >= 0)
        OR (outcome IN ('FAILED', 'CANCELED') AND row_count IS NULL AND truncated = false)
    )
);

CREATE INDEX ix_query_history_workspace_created
    ON neurosql_app.query_history (workspace_id, created_at DESC, history_id DESC);

CREATE INDEX ix_query_history_user_created
    ON neurosql_app.query_history (workspace_id, user_id, created_at DESC, history_id DESC);

COMMENT ON TABLE neurosql_app.query_history IS
    'Proposed workspace-scoped query history. Stores user-authored SQL text; API must enforce membership and retention.';

CREATE TABLE neurosql_app.saved_queries (
    saved_query_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL
        CHECK (name <> '' AND name = btrim(name)),
    description VARCHAR(500) NOT NULL DEFAULT ''
        CHECK (length(description) <= 500),
    query_text TEXT NOT NULL
        CHECK (length(query_text) BETWEEN 1 AND 50000 AND btrim(query_text) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_saved_queries_workspace_member
        FOREIGN KEY (workspace_id, owner_user_id)
        REFERENCES neurosql_app.workspace_memberships (workspace_id, user_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_saved_queries_workspace_name
    ON neurosql_app.saved_queries (workspace_id, lower(name));

CREATE INDEX ix_saved_queries_workspace_updated
    ON neurosql_app.saved_queries (workspace_id, updated_at DESC, saved_query_id DESC);

COMMENT ON TABLE neurosql_app.saved_queries IS
    'Proposed workspace-scoped saved SQL. API must enforce membership and owner/admin edit policy.';

REVOKE ALL ON TABLE neurosql_app.query_history FROM PUBLIC;
REVOKE ALL ON TABLE neurosql_app.saved_queries FROM PUBLIC;

-- History is append-only for the runtime role. The generated ID and timestamp use defaults.
GRANT SELECT (
    history_id, workspace_id, user_id, query_text, query_hash,
    outcome, row_count, execution_time_ms, truncated, created_at
) ON neurosql_app.query_history TO neurosql_auth_runtime;

GRANT INSERT (
    workspace_id, user_id, query_text, query_hash,
    outcome, row_count, execution_time_ms, truncated
) ON neurosql_app.query_history TO neurosql_auth_runtime;

-- Saved queries can be read by authorized workspace members. Application authorization
-- decides which member can edit/delete; immutable ownership and workspace columns are
-- deliberately excluded from UPDATE grants.
GRANT SELECT (
    saved_query_id, workspace_id, owner_user_id, name,
    description, query_text, created_at, updated_at
) ON neurosql_app.saved_queries TO neurosql_auth_runtime;

GRANT INSERT (
    workspace_id, owner_user_id, name, description, query_text
) ON neurosql_app.saved_queries TO neurosql_auth_runtime;

GRANT UPDATE (name, description, query_text, updated_at)
    ON neurosql_app.saved_queries TO neurosql_auth_runtime;

GRANT DELETE ON TABLE neurosql_app.saved_queries TO neurosql_auth_runtime;
