-- Spans are written for every trace and grow with traffic; legacy UAT rows are archived to
-- object storage instead of being copied here. Payload columns stay text so the timeline can
-- read metadata without pulling them.
CREATE TABLE IF NOT EXISTS app.spans (
    id text PRIMARY KEY,
    trace_id text NOT NULL,
    user_id text,
    span_id text NOT NULL,
    parent_span_id text,
    name text,
    type text,
    model text,
    status text,
    tool_call_id text,
    input text,
    output text,
    input_tokens bigint,
    output_tokens bigint,
    cached_tokens bigint,
    cost_usd double precision,
    cost_source text,
    pricing_model_id text,
    input_price_per_1m_tokens double precision,
    output_price_per_1m_tokens double precision,
    duration_ms bigint,
    error_message text,
    attributes jsonb,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz
);

CREATE UNIQUE INDEX IF NOT EXISTS spans_span_id_uidx ON app.spans (span_id);
CREATE INDEX IF NOT EXISTS spans_trace_started_idx ON app.spans (trace_id, started_at, id);
CREATE INDEX IF NOT EXISTS spans_started_idx ON app.spans (started_at, id);
CREATE INDEX IF NOT EXISTS spans_type_started_idx ON app.spans (type, started_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS spans_model_started_idx ON app.spans (model, started_at DESC);
CREATE INDEX IF NOT EXISTS spans_user_idx ON app.spans (user_id);
