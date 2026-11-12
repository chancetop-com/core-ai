CREATE TABLE IF NOT EXISTS app.sandbox_snapshots
(
    id              TEXT PRIMARY KEY,
    session_id      TEXT        NOT NULL,
    user_id         TEXT        NOT NULL,
    epoch           BIGINT      NOT NULL,
    status          TEXT        NOT NULL,
    blob_key        TEXT        NOT NULL,
    sha256          TEXT,
    size_bytes      BIGINT,
    file_count      INTEGER,
    image           TEXT,
    runtime_version TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    data            JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS sandbox_snapshots_session_created_idx
    ON app.sandbox_snapshots (session_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS sandbox_snapshots_expires_idx
    ON app.sandbox_snapshots (expires_at);

CREATE TABLE IF NOT EXISTS app.sandbox_epochs
(
    id         TEXT PRIMARY KEY,
    epoch      BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
