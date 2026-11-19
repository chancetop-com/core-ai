CREATE TABLE IF NOT EXISTS app.dataset_reports
(
    id                TEXT PRIMARY KEY,
    user_id           TEXT        NOT NULL,
    title             TEXT        NOT NULL,
    description       TEXT,
    revision          BIGINT      NOT NULL DEFAULT 1,
    spec_version      INTEGER     NOT NULL,
    engine_version    INTEGER     NOT NULL,
    spec_json         JSONB       NOT NULL,
    dataset_id        TEXT        NOT NULL,
    dependency_json   JSONB       NOT NULL,
    origin_session_id TEXT,
    origin_agent_id   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT dataset_reports_revision_positive CHECK (revision > 0)
);

CREATE INDEX IF NOT EXISTS dataset_reports_user_idx
    ON app.dataset_reports (user_id, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS dataset_reports_dataset_idx
    ON app.dataset_reports (dataset_id);

CREATE TABLE IF NOT EXISTS app.home_report_layouts
(
    user_id    TEXT PRIMARY KEY,
    revision   BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT home_report_layouts_revision_non_negative CHECK (revision >= 0)
);

CREATE TABLE IF NOT EXISTS app.home_report_pins
(
    id         TEXT PRIMARY KEY,
    user_id    TEXT        NOT NULL,
    report_id  TEXT        NOT NULL,
    position   INTEGER     NOT NULL,
    width      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT home_report_pins_width CHECK (width IN ('HALF', 'FULL')),
    CONSTRAINT home_report_pins_position_non_negative CHECK (position >= 0),
    CONSTRAINT home_report_pins_user_report UNIQUE (user_id, report_id)
);

CREATE INDEX IF NOT EXISTS home_report_pins_user_position_idx
    ON app.home_report_pins (user_id, position, id);

CREATE TABLE IF NOT EXISTS app.dataset_report_audits
(
    id                TEXT PRIMARY KEY,
    user_id           TEXT        NOT NULL,
    report_id         TEXT        NOT NULL,
    action            TEXT        NOT NULL,
    actor_user_id     TEXT        NOT NULL,
    origin_session_id TEXT,
    origin_agent_id   TEXT,
    revision          BIGINT,
    spec_hash         TEXT,
    details_json      JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS dataset_report_audits_user_idx
    ON app.dataset_report_audits (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS dataset_report_audits_report_idx
    ON app.dataset_report_audits (report_id, created_at DESC);

CREATE INDEX IF NOT EXISTS dataset_report_audits_created_idx
    ON app.dataset_report_audits (created_at);
