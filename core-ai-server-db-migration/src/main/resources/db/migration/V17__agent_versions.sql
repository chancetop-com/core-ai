CREATE TABLE IF NOT EXISTS app.agent_versions
(
    id         TEXT PRIMARY KEY,
    agent_id   TEXT        NOT NULL,
    agent_name TEXT,
    created_by TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    data       JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS agent_versions_agent_created_idx
    ON app.agent_versions (agent_id, created_at DESC, id);
