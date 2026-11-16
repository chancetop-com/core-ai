CREATE SCHEMA IF NOT EXISTS messaging;

CREATE TABLE IF NOT EXISTS messaging.session_activity
(
    session_id    TEXT PRIMARY KEY,
    last_activity BIGINT      NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS session_activity_expires_idx ON messaging.session_activity (expires_at);

CREATE TABLE IF NOT EXISTS messaging.a2a_tasks
(
    task_id    TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS a2a_tasks_expires_idx ON messaging.a2a_tasks (expires_at);

CREATE TABLE IF NOT EXISTS app.sandbox_bindings
(
    session_id TEXT PRIMARY KEY,
    sandbox_id TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
