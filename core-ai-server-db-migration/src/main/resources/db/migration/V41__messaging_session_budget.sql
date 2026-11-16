DROP TABLE IF EXISTS messaging.user_session_counts;

CREATE TABLE messaging.user_session_counts
(
    user_id    TEXT PRIMARY KEY,
    count      INTEGER     NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS user_session_counts_expires_idx ON messaging.user_session_counts (expires_at);
