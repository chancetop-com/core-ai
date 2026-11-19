CREATE TABLE IF NOT EXISTS app.home_todo_digest_runs
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS home_todo_digest_user_date_idx
    ON app.home_todo_digest_runs ((data ->> 'userId'), (data ->> 'runDate'));

CREATE INDEX IF NOT EXISTS home_todo_digest_status_idx
    ON app.home_todo_digest_runs ((data ->> 'status'), (data ->> 'startedAt'));
