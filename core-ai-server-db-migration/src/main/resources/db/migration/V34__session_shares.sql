CREATE TABLE IF NOT EXISTS app.session_shares
(
    id          TEXT PRIMARY KEY,
    session_id  TEXT        NOT NULL UNIQUE,
    token       TEXT        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    data        JSONB       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
