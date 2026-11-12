CREATE TABLE IF NOT EXISTS app.agent_runs
(
    id            TEXT PRIMARY KEY,
    agent_id      TEXT        NOT NULL,
    user_id       TEXT        NOT NULL,
    triggered_by  TEXT        NOT NULL,
    status        TEXT        NOT NULL,
    schedule_id   TEXT,
    trace_id      TEXT,
    started_at    TIMESTAMPTZ NOT NULL,
    queued_at     TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    has_artifacts BOOLEAN     NOT NULL DEFAULT false,
    output        TEXT,
    error         TEXT,
    data          JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS agent_runs_agent_started_idx ON app.agent_runs (agent_id, started_at DESC, id);
CREATE INDEX IF NOT EXISTS agent_runs_user_started_idx ON app.agent_runs (user_id, started_at DESC, id);
CREATE INDEX IF NOT EXISTS agent_runs_status_started_idx ON app.agent_runs (status, started_at);
CREATE INDEX IF NOT EXISTS agent_runs_schedule_idx ON app.agent_runs (schedule_id, status, started_at);
CREATE INDEX IF NOT EXISTS agent_runs_started_idx ON app.agent_runs (started_at DESC, id);
CREATE INDEX IF NOT EXISTS agent_runs_completed_idx ON app.agent_runs (completed_at);

