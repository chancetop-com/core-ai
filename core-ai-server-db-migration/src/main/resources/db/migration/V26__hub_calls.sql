CREATE TABLE IF NOT EXISTS app.hub_calls
(
    id             TEXT PRIMARY KEY,
    kind           TEXT        NOT NULL,
    user_id        TEXT        NOT NULL,
    user_type      TEXT,
    source         TEXT,
    target         TEXT,
    ref_id         TEXT,
    group_name     TEXT,
    name           TEXT,
    sandbox_id     TEXT,
    tool_kind      TEXT,
    session_id     TEXT,
    args_hash      TEXT,
    args_preview   TEXT,
    success        BOOLEAN,
    is_error       BOOLEAN,
    status_code    INTEGER,
    duration_ms    BIGINT,
    output_bytes   INTEGER,
    error_message  TEXT,
    task_id        TEXT,
    context_id     TEXT,
    input_tokens   BIGINT,
    output_tokens  BIGINT,
    created_at     TIMESTAMPTZ NOT NULL,
    data           JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS hub_calls_created_idx ON app.hub_calls (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS hub_calls_kind_created_idx ON app.hub_calls (kind, created_at DESC);
CREATE INDEX IF NOT EXISTS hub_calls_user_created_idx ON app.hub_calls (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS hub_calls_source_created_idx ON app.hub_calls (source, created_at DESC);
