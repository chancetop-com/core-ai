CREATE TABLE IF NOT EXISTS app.skill_runtime_stats
(
    skill_id                 TEXT        NOT NULL,
    package_digest           TEXT        NOT NULL,
    invoke_count             BIGINT      NOT NULL DEFAULT 0,
    resource_read_count      BIGINT      NOT NULL DEFAULT 0,
    materialize_fail_count   BIGINT      NOT NULL DEFAULT 0,
    attributed_script_calls  BIGINT      NOT NULL DEFAULT 0,
    attributed_script_errors BIGINT      NOT NULL DEFAULT 0,
    last_invoked_at          TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (skill_id, package_digest)
);

CREATE INDEX IF NOT EXISTS skill_runtime_stats_skill_idx
    ON app.skill_runtime_stats (skill_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS app.skill_telemetry_batches
(
    batch_id   TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS skill_telemetry_batches_expires_idx
    ON app.skill_telemetry_batches (expires_at);

CREATE TABLE IF NOT EXISTS app.skill_papercuts
(
    id                    TEXT PRIMARY KEY,
    skill_id              TEXT        NOT NULL,
    qualified_name        TEXT        NOT NULL,
    package_digest        TEXT        NOT NULL,
    dedup_key             TEXT        NOT NULL,
    category              TEXT        NOT NULL,
    status                TEXT        NOT NULL,
    summary               TEXT        NOT NULL,
    latest_observed_error TEXT,
    suggested_fix         TEXT,
    occurrence_count      INT         NOT NULL DEFAULT 1,
    reporter_user_id      TEXT,
    latest_session_id     TEXT,
    resolved_by           TEXT,
    resolved_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS skill_papercuts_skill_status_idx
    ON app.skill_papercuts (skill_id, status, last_seen_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS skill_papercuts_active_dedup_uidx
    ON app.skill_papercuts (skill_id, dedup_key)
    WHERE status IN ('OPEN', 'IGNORED');
