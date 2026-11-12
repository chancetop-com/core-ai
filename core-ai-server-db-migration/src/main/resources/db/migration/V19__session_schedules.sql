CREATE TABLE IF NOT EXISTS app.session_schedules
(
    id          TEXT PRIMARY KEY,
    session_id  TEXT        NOT NULL,
    user_id     TEXT        NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    next_run_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    data        JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS session_schedules_due_idx
    ON app.session_schedules (next_run_at) WHERE enabled = true;
CREATE INDEX IF NOT EXISTS session_schedules_session_idx
    ON app.session_schedules (session_id, created_at DESC);
