-- Workspace bootstrap/start tracking, log snippet, env presence, and wake/sleep timestamps.

ALTER TABLE app.workspaces
    ADD COLUMN IF NOT EXISTS bootstrap_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS bootstrap_script_hash  TEXT,
    ADD COLUMN IF NOT EXISTS start_script_hash      TEXT,
    ADD COLUMN IF NOT EXISTS last_bootstrap_log     TEXT,
    ADD COLUMN IF NOT EXISTS env_file_present       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_woken_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_slept_at          TIMESTAMPTZ;
