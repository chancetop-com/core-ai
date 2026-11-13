-- Trace storage on PostgreSQL (G5b). Spans are already here (V32); this adds their parent trace.
-- Payloads are text, like spans, and the list views never select the payload columns.
CREATE TABLE IF NOT EXISTS app.traces
(
    id               TEXT PRIMARY KEY,
    trace_id         TEXT        NOT NULL,
    name             TEXT,
    model            TEXT,
    type             TEXT,
    source           TEXT,
    agent_name       TEXT,
    agent_id         TEXT,
    session_id       TEXT,
    user_id          TEXT,
    status           TEXT,
    error_message    TEXT,
    input            TEXT,
    output           TEXT,
    metadata         JSONB,
    input_tokens     BIGINT,
    output_tokens    BIGINT,
    total_tokens     BIGINT,
    cached_tokens    BIGINT,
    cost_usd         DOUBLE PRECISION,
    duration_ms      BIGINT,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ
);

-- Mongo keeps trace_id unique; ingest relies on that to settle a create race.
CREATE UNIQUE INDEX IF NOT EXISTS traces_trace_id_uidx ON app.traces (trace_id);
CREATE INDEX IF NOT EXISTS traces_created_idx ON app.traces (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS traces_started_idx ON app.traces (started_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS traces_session_idx ON app.traces (session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS traces_user_created_idx ON app.traces (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS traces_user_started_idx ON app.traces (user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS traces_agent_started_idx ON app.traces (agent_id, started_at DESC);
CREATE INDEX IF NOT EXISTS traces_status_started_idx ON app.traces (status, started_at);
CREATE INDEX IF NOT EXISTS traces_model_idx ON app.traces (model);
CREATE INDEX IF NOT EXISTS traces_source_idx ON app.traces (source);
CREATE INDEX IF NOT EXISTS traces_type_idx ON app.traces (type);
CREATE INDEX IF NOT EXISTS traces_agent_name_idx ON app.traces (agent_name);
CREATE INDEX IF NOT EXISTS traces_metadata_idx ON app.traces USING gin (metadata jsonb_path_ops);

-- Deduplicated inline images referenced from trace and span payloads, keyed by content hash.
CREATE TABLE IF NOT EXISTS app.trace_payloads
(
    trace_id  TEXT NOT NULL,
    blob_hash TEXT NOT NULL,
    content   TEXT NOT NULL,
    PRIMARY KEY (trace_id, blob_hash)
);
