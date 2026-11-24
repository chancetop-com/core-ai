-- Persistent Workspace: the durable object is the volume plus its declarative config; the sandbox
-- runtime is replaceable. Volume rows are authoritative for volume state; workspaces.volume_status
-- and active_volume_generation are derived caches repaired by the reconciler.
CREATE TABLE IF NOT EXISTS app.workspaces
(
    id                       TEXT PRIMARY KEY,
    owner_user_id            TEXT        NOT NULL,
    name                     TEXT        NOT NULL,
    agent_id                 TEXT,
    is_default               BOOLEAN     NOT NULL DEFAULT FALSE,
    volume_status            TEXT        NOT NULL,
    runtime_status           TEXT        NOT NULL,
    active_volume_generation BIGINT,
    size_gb                  INTEGER     NOT NULL,
    storage_class            TEXT        NOT NULL,
    profile                  TEXT        NOT NULL DEFAULT 'workspace-default',
    mount_path               TEXT        NOT NULL DEFAULT '/workspace',
    image                    TEXT,
    runtime_version          TEXT,
    env_json                 JSONB       NOT NULL DEFAULT '{}'::jsonb,
    secret_refs_json         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    bootstrap_script         TEXT,
    start_script             TEXT,
    network_enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    network_profile          TEXT        NOT NULL DEFAULT 'public-egress',
    idle_sleep_minutes       INTEGER     NOT NULL DEFAULT 15,
    runtime_generation       BIGINT      NOT NULL DEFAULT 0,
    last_used_at             TIMESTAMPTZ,
    last_provisioned_at      TIMESTAMPTZ,
    error_code               TEXT,
    error_message            TEXT,
    deleted_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT workspaces_default_requires_agent CHECK (NOT is_default OR agent_id IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS workspaces_default_agent_idx
    ON app.workspaces (owner_user_id, agent_id) WHERE is_default AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS workspaces_owner_name_idx
    ON app.workspaces (owner_user_id, name) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS workspaces_owner_updated_idx
    ON app.workspaces (owner_user_id, updated_at DESC) WHERE deleted_at IS NULL;

-- One row per (workspace, generation): reset keeps the old row in DELETING so its PVC name and
-- failure history survive until the volume is really gone.
CREATE TABLE IF NOT EXISTS app.workspace_volumes
(
    workspace_id      TEXT        NOT NULL,
    volume_generation BIGINT      NOT NULL,
    pvc_name          TEXT        NOT NULL,
    provider          TEXT        NOT NULL,
    size_gb           INTEGER     NOT NULL,
    storage_class     TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    node_name         TEXT,
    zone              TEXT,
    bound_at          TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, volume_generation)
);

CREATE UNIQUE INDEX IF NOT EXISTS workspace_volumes_provider_pvc_idx
    ON app.workspace_volumes (provider, pvc_name);
CREATE UNIQUE INDEX IF NOT EXISTS workspace_volumes_active_idx
    ON app.workspace_volumes (workspace_id) WHERE status IN ('PROVISIONING', 'READY', 'ERROR');

-- The current runtime pointer and the hub's bound session. A runtime is replaceable; this table is
-- the only place that knows which sandbox currently owns the volume mount.
CREATE TABLE IF NOT EXISTS app.workspace_bindings
(
    workspace_id       TEXT PRIMARY KEY,
    sandbox_id         TEXT        NOT NULL,
    provider           TEXT        NOT NULL,
    runtime_generation BIGINT      NOT NULL,
    pod_id             TEXT,
    bound_session_id   TEXT,
    runtime_started_at TIMESTAMPTZ,
    bound_at           TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS workspace_bindings_session_idx
    ON app.workspace_bindings (bound_session_id) WHERE bound_session_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS app.workspace_leases
(
    workspace_id       TEXT PRIMARY KEY,
    holder_id          TEXT        NOT NULL,
    lease_token        TEXT        NOT NULL,
    lease_kind         TEXT        NOT NULL,
    session_id         TEXT,
    turn_id            TEXT,
    runtime_generation BIGINT,
    expires_at         TIMESTAMPTZ NOT NULL,
    renewed_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS workspace_leases_expires_idx ON app.workspace_leases (expires_at);

CREATE TABLE IF NOT EXISTS app.workspace_async_tasks
(
    workspace_id       TEXT        NOT NULL,
    task_id            TEXT        NOT NULL,
    session_id         TEXT        NOT NULL,
    turn_id            TEXT,
    runtime_generation BIGINT,
    tool               TEXT,
    status             TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    last_polled_at     TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    PRIMARY KEY (workspace_id, task_id)
);

CREATE INDEX IF NOT EXISTS workspace_async_tasks_status_idx
    ON app.workspace_async_tasks (workspace_id, status);
CREATE INDEX IF NOT EXISTS workspace_async_tasks_polled_idx
    ON app.workspace_async_tasks (status, last_polled_at);
CREATE INDEX IF NOT EXISTS workspace_async_tasks_session_idx
    ON app.workspace_async_tasks (session_id, task_id);

CREATE TABLE IF NOT EXISTS app.workspace_job_leases
(
    job_name   TEXT PRIMARY KEY,
    holder_id  TEXT        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS app.workspace_events
(
    id           TEXT PRIMARY KEY,
    workspace_id TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    actor        TEXT,
    payload_json JSONB,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS workspace_events_workspace_idx
    ON app.workspace_events (workspace_id, created_at DESC);

-- Authoritative workspace binding of a session. The data JSONB deliberately stays free of these
-- fields: a binding change must not rewrite the whole session document.
ALTER TABLE app.chat_sessions ADD COLUMN IF NOT EXISTS workspace_id TEXT;
ALTER TABLE app.chat_sessions ADD COLUMN IF NOT EXISTS workspace_generation BIGINT;
CREATE INDEX IF NOT EXISTS chat_sessions_workspace_idx
    ON app.chat_sessions (workspace_id) WHERE workspace_id IS NOT NULL;
