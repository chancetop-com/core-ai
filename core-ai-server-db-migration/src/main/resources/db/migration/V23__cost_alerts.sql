CREATE TABLE IF NOT EXISTS app.cost_alert_rules
(
    id          TEXT PRIMARY KEY,
    name        TEXT        NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    metric      TEXT        NOT NULL,
    scope       TEXT        NOT NULL,
    scope_value TEXT        NOT NULL,
    threshold   DOUBLE PRECISION NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    data        JSONB       NOT NULL
);

CREATE TABLE IF NOT EXISTS app.cost_alert_events
(
    id           TEXT PRIMARY KEY,
    rule_id      TEXT        NOT NULL,
    rule_name    TEXT        NOT NULL,
    date         TIMESTAMPTZ NOT NULL,
    scope        TEXT        NOT NULL,
    scope_value  TEXT        NOT NULL,
    metric       TEXT        NOT NULL,
    threshold    DOUBLE PRECISION NOT NULL,
    actual_value DOUBLE PRECISION NOT NULL,
    status       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    data         JSONB       NOT NULL,
    CONSTRAINT cost_alert_events_unique UNIQUE (rule_id, scope, scope_value, date)
);

CREATE INDEX IF NOT EXISTS cost_alert_events_created_idx
    ON app.cost_alert_events (created_at DESC);
