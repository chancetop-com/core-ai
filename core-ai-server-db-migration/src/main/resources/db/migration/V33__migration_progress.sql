-- Resumable progress for migrations whose data no longer fits a single startup transaction.
-- A row is written by the instance that owns the copy and deleted when the marker is written.
CREATE TABLE IF NOT EXISTS app.migration_progress (
    id         text PRIMARY KEY,
    last_key   text NOT NULL,
    updated_at timestamptz NOT NULL
);
