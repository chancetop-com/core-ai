CREATE TABLE IF NOT EXISTS app.agents
(
    id                    TEXT PRIMARY KEY,
    user_id               TEXT        NOT NULL,
    name                  TEXT        NOT NULL,
    name_key              TEXT,
    type                  TEXT        NOT NULL,
    status                TEXT        NOT NULL,
    system_default        BOOLEAN,
    forked_from           TEXT,
    has_published_config  BOOLEAN     NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    data                  JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS agents_user_updated_idx ON app.agents (user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS agents_user_created_idx ON app.agents (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS agents_user_name_idx ON app.agents (user_id, name);
CREATE INDEX IF NOT EXISTS agents_user_name_key_idx ON app.agents (user_id, name_key);
CREATE INDEX IF NOT EXISTS agents_user_type_name_key_idx ON app.agents (user_id, type, name_key, id);
CREATE INDEX IF NOT EXISTS agents_status_type_name_key_idx ON app.agents (status, type, name_key, id);
CREATE INDEX IF NOT EXISTS agents_system_default_idx ON app.agents (system_default) WHERE system_default = true;
CREATE INDEX IF NOT EXISTS agents_forked_from_idx ON app.agents (forked_from) WHERE forked_from IS NOT NULL;
