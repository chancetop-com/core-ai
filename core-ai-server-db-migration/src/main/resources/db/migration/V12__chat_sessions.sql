CREATE TABLE IF NOT EXISTS app.chat_sessions
(
    id                   TEXT PRIMARY KEY,
    user_id              TEXT        NOT NULL,
    agent_id             TEXT,
    source               TEXT,
    comparison_id        TEXT,
    comparison_pair_id   TEXT,
    comparison_variant   TEXT,
    title                TEXT,
    message_count        BIGINT,
    created_at           TIMESTAMPTZ NOT NULL,
    last_message_at      TIMESTAMPTZ,
    notify_on_complete   BOOLEAN,
    deleted_at           TIMESTAMPTZ,
    data                 JSONB       NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS chat_sessions_user_last_message_idx
    ON app.chat_sessions (user_id, last_message_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS chat_sessions_user_created_idx
    ON app.chat_sessions (user_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS chat_sessions_agent_last_message_idx
    ON app.chat_sessions (agent_id, last_message_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS chat_sessions_comparison_pair_idx
    ON app.chat_sessions (comparison_pair_id) WHERE comparison_pair_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS chat_sessions_comparison_idx
    ON app.chat_sessions (comparison_id) WHERE comparison_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS app.g2_migration_markers
(
    id           TEXT PRIMARY KEY,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
