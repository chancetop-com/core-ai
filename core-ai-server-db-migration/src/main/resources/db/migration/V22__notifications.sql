CREATE TABLE IF NOT EXISTS app.notifications
(
    id         TEXT PRIMARY KEY,
    user_id    TEXT        NOT NULL,
    category   TEXT,
    type       TEXT        NOT NULL,
    title      TEXT        NOT NULL,
    message    TEXT        NOT NULL,
    agent_id   TEXT,
    session_id TEXT,
    status     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at    TIMESTAMPTZ,
    data       JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS notifications_user_created_idx
    ON app.notifications (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS notifications_user_status_created_idx
    ON app.notifications (user_id, status, created_at DESC);
