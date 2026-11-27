-- Cached filesystem usage sampled from the workspace runtime via GET /workspace/stat,
-- so workspace listing and details never block on a live df call or require a running runtime.
CREATE TABLE IF NOT EXISTS app.workspace_usage
(
    workspace_id      TEXT PRIMARY KEY,
    used_bytes        BIGINT,
    capacity_bytes    BIGINT,
    available_bytes   BIGINT,
    inodes_used       BIGINT,
    inodes_total      BIGINT,
    volume_generation BIGINT,
    sampled_at        TIMESTAMPTZ NOT NULL
);
