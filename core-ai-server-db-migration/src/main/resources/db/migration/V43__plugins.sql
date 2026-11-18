CREATE TABLE IF NOT EXISTS app.plugins
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS plugins_name_idx ON app.plugins ((data ->> 'name'));

CREATE INDEX IF NOT EXISTS plugins_updated_idx ON app.plugins (updated_at DESC);
