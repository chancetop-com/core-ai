ALTER TABLE messaging.pods ADD COLUMN IF NOT EXISTS command_backend TEXT NOT NULL DEFAULT 'redis';

CREATE TABLE IF NOT EXISTS messaging.commands (
    id           BIGSERIAL PRIMARY KEY,
    type         TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    user_id      TEXT,
    payload      TEXT NOT NULL,
    request_id   TEXT,
    target_pod   TEXT,
    status       TEXT NOT NULL DEFAULT 'pending',
    claimed_by   TEXT,
    claimed_at   TIMESTAMPTZ,
    attempts     INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error   TEXT
);

CREATE INDEX IF NOT EXISTS commands_pending_idx
    ON messaging.commands (status, available_at, target_pod, id);
