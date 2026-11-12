CREATE TABLE IF NOT EXISTS app.workflow_definitions
(
    id                   TEXT PRIMARY KEY,
    user_id              TEXT,
    name                 TEXT,
    mode                 TEXT,
    draft_graph          TEXT,
    visibility            TEXT,
    status                TEXT,
    published_version_id  TEXT,
    published_version     INTEGER,
    created_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ,
    archived_at           TIMESTAMPTZ,
    archived_by           TEXT,
    data                  JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS workflow_definitions_user_updated_idx
    ON app.workflow_definitions (user_id, updated_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS workflow_definitions_status_idx
    ON app.workflow_definitions (status);
CREATE INDEX IF NOT EXISTS workflow_definitions_published_idx
    ON app.workflow_definitions (visibility, status, published_version_id);

CREATE TABLE IF NOT EXISTS app.workflow_published_versions
(
    id            TEXT PRIMARY KEY,
    workflow_id   TEXT        NOT NULL,
    version       INTEGER,
    preview       BOOLEAN,
    status        TEXT,
    sha256        TEXT,
    published_by  TEXT,
    published_at  TIMESTAMPTZ,
    data          JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS workflow_published_versions_workflow_idx
    ON app.workflow_published_versions (workflow_id, version DESC NULLS LAST);
CREATE UNIQUE INDEX IF NOT EXISTS workflow_published_versions_unique_version
    ON app.workflow_published_versions (workflow_id, version)
    WHERE preview IS NOT TRUE;
CREATE INDEX IF NOT EXISTS workflow_published_versions_preview_idx
    ON app.workflow_published_versions (preview, published_at);

CREATE TABLE IF NOT EXISTS app.workflow_runs
(
    id             TEXT PRIMARY KEY,
    workflow_id    TEXT        NOT NULL,
    version_id     TEXT,
    user_id        TEXT,
    mode           TEXT,
    session_id     TEXT,
    triggered_by   TEXT,
    preview        BOOLEAN,
    parent_run_id  TEXT,
    status         TEXT,
    visibility     TEXT,
    claimed_by     TEXT,
    lease_until    TIMESTAMPTZ,
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ,
    output         TEXT,
    error          TEXT,
    artifacts      JSONB,
    data           JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS workflow_runs_workflow_created_idx
    ON app.workflow_runs (workflow_id, created_at DESC NULLS LAST, id);
CREATE INDEX IF NOT EXISTS workflow_runs_claim_idx
    ON app.workflow_runs (status, lease_until);
CREATE INDEX IF NOT EXISTS workflow_runs_parent_idx
    ON app.workflow_runs (parent_run_id);
CREATE INDEX IF NOT EXISTS workflow_runs_started_idx
    ON app.workflow_runs (started_at DESC NULLS LAST, id);
CREATE INDEX IF NOT EXISTS workflow_runs_user_idx
    ON app.workflow_runs (user_id, created_at DESC NULLS LAST);

CREATE TABLE IF NOT EXISTS app.workflow_node_runs
(
    id               TEXT PRIMARY KEY,
    run_id           TEXT        NOT NULL,
    workflow_id      TEXT,
    node_id          TEXT,
    node_type        TEXT,
    scope_path_key   TEXT,
    status           TEXT,
    input_json       TEXT,
    output           TEXT,
    child_run_id     TEXT,
    preview          BOOLEAN,
    error            TEXT,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ,
    artifacts        JSONB,
    chosen_edge_ids  JSONB,
    data             JSONB NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS workflow_node_runs_unique_node
    ON app.workflow_node_runs (run_id, node_id, scope_path_key);
CREATE INDEX IF NOT EXISTS workflow_node_runs_run_created_idx
    ON app.workflow_node_runs (run_id, created_at, id);
CREATE INDEX IF NOT EXISTS workflow_node_runs_status_idx
    ON app.workflow_node_runs (run_id, status);
CREATE INDEX IF NOT EXISTS workflow_node_runs_child_idx
    ON app.workflow_node_runs (child_run_id);
CREATE INDEX IF NOT EXISTS workflow_node_runs_end_idx
    ON app.workflow_node_runs (run_id, node_type, status);
