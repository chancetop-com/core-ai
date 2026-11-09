CREATE TABLE IF NOT EXISTS app.system_settings (
    id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
