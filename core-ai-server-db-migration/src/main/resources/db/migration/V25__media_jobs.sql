CREATE TABLE IF NOT EXISTS app.media_jobs
(
    id                    TEXT PRIMARY KEY,
    user_id               TEXT,
    session_id            TEXT,
    agent_run_id          TEXT,
    state                 TEXT,
    media_type            TEXT,
    cost_source           TEXT,
    file_id               TEXT,
    artifact_state        TEXT,
    artifact_attempts     INTEGER,
    artifact_claimed_at   TIMESTAMPTZ,
    artifact_next_attempt_at TIMESTAMPTZ,
    poster_file_id        TEXT,
    poster_attempted_at   TIMESTAMPTZ,
    artifact_stored_at    TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    data                  JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS media_jobs_created_idx ON app.media_jobs (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS media_jobs_user_created_idx ON app.media_jobs (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS media_jobs_type_created_idx ON app.media_jobs (media_type, created_at DESC);
CREATE INDEX IF NOT EXISTS media_jobs_completed_idx ON app.media_jobs (completed_at, id);
CREATE INDEX IF NOT EXISTS media_jobs_artifact_idx ON app.media_jobs (artifact_state, completed_at);
CREATE INDEX IF NOT EXISTS media_jobs_updated_idx ON app.media_jobs (updated_at);
