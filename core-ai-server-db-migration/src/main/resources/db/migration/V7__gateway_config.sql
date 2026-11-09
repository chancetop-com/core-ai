CREATE TABLE IF NOT EXISTS app.gateway_provider (
    id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS gateway_provider_name_idx ON app.gateway_provider ((data ->> 'name'));

CREATE TABLE IF NOT EXISTS app.gateway_model (
    id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS gateway_model_model_id_idx ON app.gateway_model ((data ->> 'modelId'));
CREATE INDEX IF NOT EXISTS gateway_model_provider_id_idx ON app.gateway_model ((data ->> 'providerId'));
