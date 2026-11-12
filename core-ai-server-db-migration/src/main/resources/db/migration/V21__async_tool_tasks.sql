CREATE TABLE IF NOT EXISTS app.async_tool_tasks
(
    id         TEXT PRIMARY KEY,
    data       TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS async_tool_tasks_updated_idx
    ON app.async_tool_tasks (updated_at DESC);
