CREATE TABLE IF NOT EXISTS app.conversation_requests
(
    request_key   TEXT PRIMARY KEY,
    subject       TEXT        NOT NULL,
    kind          TEXT        NOT NULL,
    session_id    TEXT,
    payload_hash  TEXT        NOT NULL,
    status        TEXT        NOT NULL,
    response_json TEXT,
    error_message TEXT,
    claim_token   TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS conversation_requests_session_idx
    ON app.conversation_requests (session_id);

CREATE INDEX IF NOT EXISTS conversation_requests_expires_idx
    ON app.conversation_requests (expires_at);
