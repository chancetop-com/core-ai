CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS app.chat_messages
(
    id         TEXT PRIMARY KEY,
    session_id TEXT        NOT NULL,
    seq        BIGINT      NOT NULL,
    role       TEXT,
    trace_id   TEXT,
    content    TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chat_messages_session_seq UNIQUE (session_id, seq)
);

CREATE INDEX IF NOT EXISTS chat_messages_session_seq_idx ON app.chat_messages (session_id, seq);
CREATE INDEX IF NOT EXISTS chat_messages_session_created_idx ON app.chat_messages (session_id, created_at);
CREATE INDEX IF NOT EXISTS chat_messages_session_role_idx ON app.chat_messages (session_id, role);
CREATE INDEX IF NOT EXISTS chat_messages_trace_idx ON app.chat_messages (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS chat_messages_created_idx ON app.chat_messages (created_at);
CREATE INDEX IF NOT EXISTS chat_messages_content_trgm_idx ON app.chat_messages USING gin (content gin_trgm_ops);
