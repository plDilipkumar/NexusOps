-- PROPOSED ONLY: one-time email-verification and password-reset token storage.
-- Apply only after V1/V2/V3 on a temporary Neon branch. Never apply to Neon main
-- without explicit user approval.
--
-- Only SHA-256 hex digests of high-entropy random tokens are stored. Raw tokens
-- must be returned to the caller once and delivered out-of-band; never persist them.
-- Token consumption is irreversible so a consumed token cannot be replayed by
-- changing consumed_at back to NULL.

CREATE TABLE neurosql_app.account_action_tokens (
    token_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL
        REFERENCES neurosql_app.app_users(user_id) ON DELETE CASCADE,
    purpose VARCHAR(24) NOT NULL
        CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    token_hash VARCHAR(64) NOT NULL
        CONSTRAINT uq_account_action_tokens_token_hash UNIQUE
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT account_action_token_expiry_after_creation
        CHECK (expires_at > created_at),
    CONSTRAINT account_action_token_consumed_after_creation
        CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX ix_account_action_tokens_user_purpose_created
    ON neurosql_app.account_action_tokens (user_id, purpose, created_at DESC);

CREATE INDEX ix_account_action_tokens_unconsumed_expiry
    ON neurosql_app.account_action_tokens (expires_at)
    WHERE consumed_at IS NULL;

COMMENT ON TABLE neurosql_app.account_action_tokens IS
    'Proposed single-use verification/reset tokens. Stores token digests only, never raw tokens.';

CREATE FUNCTION neurosql_app.prevent_account_action_token_reactivation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.consumed_at IS NOT NULL
       AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at THEN
        RAISE EXCEPTION 'Consumed account action tokens cannot be reactivated'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'account_action_token_single_use';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_account_action_tokens_single_use
BEFORE UPDATE OF consumed_at ON neurosql_app.account_action_tokens
FOR EACH ROW EXECUTE FUNCTION neurosql_app.prevent_account_action_token_reactivation();

REVOKE ALL ON TABLE neurosql_app.account_action_tokens FROM PUBLIC;
REVOKE ALL ON FUNCTION neurosql_app.prevent_account_action_token_reactivation() FROM PUBLIC;

-- Registration creates an unverified account; the runtime role cannot set the
-- verification timestamp during INSERT. Password reset and verification update
-- only their dedicated fields, not email, status, or account ownership.
GRANT INSERT (email, password_hash, display_name)
    ON neurosql_app.app_users TO neurosql_auth_runtime;
GRANT UPDATE (password_hash, email_verified_at, updated_at)
    ON neurosql_app.app_users TO neurosql_auth_runtime;

GRANT SELECT (token_id, user_id, purpose, token_hash, created_at, expires_at, consumed_at)
    ON neurosql_app.account_action_tokens TO neurosql_auth_runtime;
GRANT INSERT (user_id, purpose, token_hash, expires_at)
    ON neurosql_app.account_action_tokens TO neurosql_auth_runtime;
GRANT UPDATE (consumed_at)
    ON neurosql_app.account_action_tokens TO neurosql_auth_runtime;
