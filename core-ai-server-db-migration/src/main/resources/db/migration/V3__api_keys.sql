CREATE TABLE IF NOT EXISTS app.api_keys (
    id text PRIMARY KEY,
    key_hash text NOT NULL,
    key_prefix text,
    user_id text NOT NULL,
    scope text,
    metadata jsonb,
    status text,
    expires_at timestamptz,
    created_at timestamptz,
    last_used_at timestamptz,
    revoked_at timestamptz
);

CREATE UNIQUE INDEX IF NOT EXISTS api_keys_key_hash_idx ON app.api_keys (key_hash);
CREATE INDEX IF NOT EXISTS api_keys_user_created_idx ON app.api_keys (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS api_keys_user_scope_status_idx ON app.api_keys (user_id, scope, status);
