CREATE TABLE IF NOT EXISTS app.agent_schedules
(
    id          TEXT PRIMARY KEY,
    agent_id    TEXT        NOT NULL,
    user_id     TEXT        NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    next_run_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    data        JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS agent_schedules_due_idx
    ON app.agent_schedules (next_run_at) WHERE enabled = true;
CREATE INDEX IF NOT EXISTS agent_schedules_agent_idx
    ON app.agent_schedules (agent_id, created_at DESC);
