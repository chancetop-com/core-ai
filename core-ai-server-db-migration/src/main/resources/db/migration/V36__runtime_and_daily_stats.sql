-- Runtime and daily analytics storage on PostgreSQL (G5c). Traces and spans are already here
-- (V32, V35); these are the per-minute runtime watermarks and the two daily rollup tables.
CREATE TABLE IF NOT EXISTS app.runtime_samples
(
    id                                      TEXT PRIMARY KEY,
    pod                                     TEXT,
    collected_at                            TIMESTAMPTZ,
    heap_used_percent                       DOUBLE PRECISION,
    heap_used                               BIGINT,
    heap_max                                BIGINT,
    heap_before_gc                          BIGINT,
    heap_before_gc_percent                  DOUBLE PRECISION,
    heap_after_gc                           BIGINT,
    heap_after_gc_percent                   DOUBLE PRECISION,
    gc_id                                   BIGINT,
    gc_age_ms                               BIGINT,
    container_memory                        BIGINT,
    container_memory_max                    BIGINT,
    container_memory_percent                DOUBLE PRECISION,
    rss                                     BIGINT,
    sessions_active                         INTEGER,
    session_estimate_version                INTEGER,
    sessions_estimated_bytes_total          BIGINT,
    sessions_estimated_bytes_p50            BIGINT,
    sessions_estimated_bytes_p90            BIGINT,
    sessions_estimated_bytes_p99            BIGINT,
    sessions_estimated_bytes_max            BIGINT,
    sessions_context_tokens_total           BIGINT,
    sessions_context_tokens_p50             BIGINT,
    sessions_context_tokens_p90             BIGINT,
    sessions_inline_image_bytes_total       BIGINT,
    sessions_idle_seconds_p50               BIGINT,
    sessions_idle_seconds_p90               BIGINT,
    session_turn_bucket_counts              JSONB,
    session_turn_bucket_estimated_bytes     JSONB,
    session_turn_bucket_estimated_bytes_p50 JSONB,
    session_turn_bucket_estimated_bytes_p90 JSONB,
    session_turn_bucket_context_tokens      JSONB,
    sessions_created_total                  BIGINT,
    sessions_closed_total                   BIGINT,
    sessions_idle_evicted_total             BIGINT,
    in_flight_turns                         INTEGER,
    turns_saturated_total                   BIGINT,
    turns_rejected_total                    BIGINT,
    runs_active                             INTEGER,
    runs_queued                             INTEGER,
    runs_rejected_total                     BIGINT,
    sandboxes_live                          INTEGER,
    sandboxes_rejected_total                BIGINT,
    span_ingest_depth                       INTEGER,
    span_ingest_dropped_total               BIGINT,
    claims_live                             INTEGER,
    direct_crs_live                         INTEGER,
    creates_total                           BIGINT,
    creates_failed_total                    BIGINT
);
-- the dashboard only ever reads a recent window; DESC keeps the newest rows at the front
CREATE INDEX IF NOT EXISTS runtime_samples_collected_idx ON app.runtime_samples (collected_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS app.trace_daily_stats
(
    id                TEXT PRIMARY KEY,
    user_id           TEXT,
    agent_id          TEXT,
    date              TIMESTAMPTZ,
    input_tokens      BIGINT,
    output_tokens     BIGINT,
    total_tokens      BIGINT,
    cached_tokens     BIGINT,
    cost_usd          DOUBLE PRECISION,
    call_count        BIGINT,
    avg_total_tokens  DOUBLE PRECISION,
    avg_cost_usd      DOUBLE PRECISION,
    p90_total_tokens  DOUBLE PRECISION,
    p90_cost_usd      DOUBLE PRECISION,
    session_count     BIGINT
);
CREATE INDEX IF NOT EXISTS trace_daily_stats_user_date_idx ON app.trace_daily_stats (user_id, date);
CREATE INDEX IF NOT EXISTS trace_daily_stats_date_idx ON app.trace_daily_stats (date);

CREATE TABLE IF NOT EXISTS app.analytics_daily_stats
(
    id                TEXT PRIMARY KEY,
    user_id           TEXT,
    agent_id          TEXT,
    agent_name        TEXT,
    source            TEXT,
    model             TEXT,
    provider_id       TEXT,
    provider_name     TEXT,
    date              TIMESTAMPTZ,
    input_tokens      BIGINT,
    output_tokens     BIGINT,
    total_tokens      BIGINT,
    cached_tokens     BIGINT,
    cost_usd          DOUBLE PRECISION,
    call_count        BIGINT,
    avg_input_tokens  DOUBLE PRECISION,
    avg_output_tokens DOUBLE PRECISION,
    avg_total_tokens  DOUBLE PRECISION,
    avg_cost_usd      DOUBLE PRECISION,
    max_total_tokens  BIGINT,
    max_cost_usd      DOUBLE PRECISION,
    p90_total_tokens  DOUBLE PRECISION,
    p90_cost_usd      DOUBLE PRECISION,
    session_count     BIGINT
);
CREATE INDEX IF NOT EXISTS analytics_daily_stats_date_idx ON app.analytics_daily_stats (date);
CREATE INDEX IF NOT EXISTS analytics_daily_stats_user_date_idx ON app.analytics_daily_stats (user_id, date);
