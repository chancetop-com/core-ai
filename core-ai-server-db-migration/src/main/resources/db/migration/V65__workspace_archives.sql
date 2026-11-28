CREATE TABLE IF NOT EXISTS app.workspace_archives
(
    id                TEXT PRIMARY KEY,
    workspace_id      TEXT        NOT NULL,
    volume_generation INTEGER     NOT NULL,
    blob_key          TEXT        NOT NULL,
    sha256            TEXT        NOT NULL,
    size_bytes        BIGINT      NOT NULL,
    file_count        BIGINT,
    image             TEXT,
    status            TEXT        NOT NULL,
    failure_reason    TEXT,
    created_by        TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS workspace_archives_workspace_created_idx
    ON app.workspace_archives (workspace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS workspace_archives_status_created_idx
    ON app.workspace_archives (status, created_at) WHERE status <> 'DELETED';
