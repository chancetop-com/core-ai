CREATE TABLE IF NOT EXISTS app.session_feedback
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS session_feedback_user_idx ON app.session_feedback ((data ->> 'userId'));
CREATE INDEX IF NOT EXISTS session_feedback_session_idx ON app.session_feedback ((data ->> 'sessionId'));

CREATE TABLE IF NOT EXISTS app.session_notices
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS session_notices_target_idx
    ON app.session_notices ((data ->> 'userId'), (data ->> 'channelId'), (data ->> 'recipient'), (data ->> 'answered'));
CREATE INDEX IF NOT EXISTS session_notices_created_idx ON app.session_notices ((data ->> 'createdAt'));

CREATE TABLE IF NOT EXISTS app.user_channel_targets
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS app.session_attachment_ref
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS session_attachment_session_idx
    ON app.session_attachment_ref ((data ->> 'sessionId'), (data ->> 'userId'), (data ->> 'kind'), (data ->> 'createdAt'));

CREATE TABLE IF NOT EXISTS app.gemini_files
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS gemini_files_source_idx
    ON app.gemini_files ((data ->> 'userId'), (data ->> 'providerId'), (data ->> 'upstreamModel'),
                         (data ->> 'container'), (data ->> 'blobName'), (data ->> 'sourceETag'));
CREATE INDEX IF NOT EXISTS gemini_files_expires_idx ON app.gemini_files ((data ->> 'expiresAt'));

CREATE TABLE IF NOT EXISTS app.user_reports
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS user_reports_user_updated_idx ON app.user_reports ((data ->> 'userId'), (data ->> 'updatedAt'));
CREATE INDEX IF NOT EXISTS user_reports_user_created_idx ON app.user_reports ((data ->> 'userId'), (data ->> 'createdAt'));

CREATE TABLE IF NOT EXISTS app.user_todos
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS user_todos_user_created_idx ON app.user_todos ((data ->> 'userId'), (data ->> 'createdAt'));
CREATE INDEX IF NOT EXISTS user_todos_user_completed_idx ON app.user_todos ((data ->> 'userId'), (data ->> 'completed'));
