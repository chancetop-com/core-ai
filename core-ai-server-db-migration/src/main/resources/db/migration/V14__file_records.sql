CREATE TABLE IF NOT EXISTS app.file_records
(
    id                  TEXT PRIMARY KEY,
    user_id             TEXT        NOT NULL,
    file_name           TEXT        NOT NULL,
    content_type        TEXT,
    size                BIGINT,
    content_hash        TEXT,
    storage_path        TEXT,
    thumb_storage_path  TEXT,
    share_token         TEXT,
    shared_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS file_records_user_created_idx ON app.file_records (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS file_records_user_hash_idx ON app.file_records (user_id, content_hash) WHERE content_hash IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS file_records_share_token_idx ON app.file_records (share_token) WHERE share_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS file_records_shared_at_idx ON app.file_records (shared_at DESC) WHERE share_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS file_records_created_idx ON app.file_records (created_at);
