-- Memory experiment storage on PostgreSQL (G4 completion). Configs are one per agent; runs are the
-- per-execution policy snapshot plus the outcome patched later from feedback.
CREATE TABLE IF NOT EXISTS app.agent_memory_experiment_configs
(
    id                    TEXT PRIMARY KEY,
    agent_id              TEXT NOT NULL,
    enabled               BOOLEAN,
    injection_probability DOUBLE PRECISION,
    enabled_layers        JSONB,
    top_k                 INTEGER,
    ranking_strategy      TEXT,
    injection_mode        TEXT,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS agent_memory_experiment_configs_agent_idx
    ON app.agent_memory_experiment_configs (agent_id);

CREATE TABLE IF NOT EXISTS app.agent_memory_experiment_runs
(
    id                     TEXT PRIMARY KEY,
    agent_id               TEXT,
    session_id             TEXT,
    run_id                 TEXT,
    enabled                BOOLEAN,
    enabled_layers         JSONB,
    ranking_strategy       TEXT,
    top_k                  INTEGER,
    injection_probability  DOUBLE PRECISION,
    injection_mode         TEXT,
    injection_decision     BOOLEAN,
    injected_memory_ids    JSONB,
    injected_memory_count  INTEGER,
    layer_breakdown        JSONB,
    prompt_tokens          BIGINT,
    attributions           JSONB,
    outcome                TEXT,
    user_rating            INTEGER,
    created_at             TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS agent_memory_experiment_runs_agent_idx
    ON app.agent_memory_experiment_runs (agent_id, created_at DESC);
CREATE INDEX IF NOT EXISTS agent_memory_experiment_runs_session_idx
    ON app.agent_memory_experiment_runs (session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS agent_memory_experiment_runs_run_idx
    ON app.agent_memory_experiment_runs (run_id);
