CREATE TABLE IF NOT EXISTS app.background_tasks
(
    id           TEXT PRIMARY KEY,
    type         TEXT,
    status       TEXT,
    status_text  TEXT,
    claimed_by   TEXT,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    retry_count  INTEGER,
    data         JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS background_tasks_type_id_idx
    ON app.background_tasks (type, id DESC);
