-- Durable records of server-side background tasks (subagents / unintercepted background tools).
-- Survives server restarts so terminal results remain queryable and orphaned running tasks are
-- surfaced as LOST instead of silently disappearing.
CREATE TABLE IF NOT EXISTS app.server_background_tasks
(
    task_id     TEXT PRIMARY KEY,
    session_id  TEXT        NOT NULL,
    owner_pod   TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    result      TEXT,
    error       TEXT,
    output_ref  TEXT,
    notified    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS server_background_tasks_session_idx
    ON app.server_background_tasks (session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS server_background_tasks_status_pod_idx
    ON app.server_background_tasks (status, owner_pod);
