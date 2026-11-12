CREATE TABLE IF NOT EXISTS app.agent_comparisons
(
    id         TEXT PRIMARY KEY,
    agent_id   TEXT,
    agent_name TEXT,
    status     TEXT,
    created_by TEXT,
    created_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    ended_at   TIMESTAMPTZ,
    data       JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS agent_comparisons_agent_created_idx
    ON app.agent_comparisons (agent_id, created_at DESC);
CREATE INDEX IF NOT EXISTS agent_comparisons_creator_created_idx
    ON app.agent_comparisons (created_by, created_at DESC);
CREATE INDEX IF NOT EXISTS agent_comparisons_status_idx
    ON app.agent_comparisons (status);

CREATE TABLE IF NOT EXISTS app.comparison_votes
(
    id            TEXT PRIMARY KEY,
    comparison_id TEXT        NOT NULL,
    pair_id       TEXT,
    session_a     TEXT        NOT NULL,
    session_b     TEXT,
    turn          BIGINT      NOT NULL,
    chosen        TEXT,
    user_id       TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    data          JSONB       NOT NULL,
    CONSTRAINT comparison_votes_unique UNIQUE (comparison_id, session_a, turn)
);

CREATE INDEX IF NOT EXISTS comparison_votes_comparison_created_idx
    ON app.comparison_votes (comparison_id, created_at);
CREATE INDEX IF NOT EXISTS comparison_votes_session_idx
    ON app.comparison_votes (comparison_id, session_a);

