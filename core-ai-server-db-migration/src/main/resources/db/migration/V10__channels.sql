CREATE TABLE IF NOT EXISTS app.channels
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_channels_channel_type ON app.channels ((data ->> 'channelType'));

CREATE TABLE IF NOT EXISTS app.ocg_configs
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ocg_configs_channel_id ON app.ocg_configs ((data ->> 'channelId'));
