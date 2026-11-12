CREATE TABLE IF NOT EXISTS app.replay_experiments
(
    id              TEXT PRIMARY KEY,
    user_id         TEXT,
    origin          TEXT,
    trace_id        TEXT,
    span_id         TEXT,
    agent_id        TEXT,
    agent_name      TEXT,
    session_id      TEXT,
    trace_source    TEXT,
    span_name       TEXT,
    original_model  TEXT,
    run_count       INTEGER,
    note            TEXT,
    draft_request   TEXT,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    data            JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS replay_experiments_user_created_idx
    ON app.replay_experiments (user_id, created_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS replay_experiments_origin_created_idx
    ON app.replay_experiments (origin, created_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS replay_experiments_agent_created_idx
    ON app.replay_experiments (agent_id, created_at DESC NULLS LAST);

CREATE TABLE IF NOT EXISTS app.replay_runs
(
    id                TEXT PRIMARY KEY,
    experiment_id     TEXT NOT NULL,
    user_id           TEXT,
    label             TEXT,
    model             TEXT,
    temperature       DOUBLE PRECISION,
    reasoning_effort  TEXT,
    sample_count      INTEGER,
    status            TEXT,
    created_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    request           TEXT,
    data              JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS replay_runs_experiment_created_idx
    ON app.replay_runs (experiment_id, created_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS replay_runs_user_created_idx
    ON app.replay_runs (user_id, created_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS replay_runs_status_idx
    ON app.replay_runs (status);
