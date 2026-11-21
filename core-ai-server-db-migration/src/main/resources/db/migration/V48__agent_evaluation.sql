CREATE TABLE IF NOT EXISTS app.eval_evaluations
(
    id                  TEXT PRIMARY KEY,
    owner_id            TEXT        NOT NULL,
    agent_id            TEXT        NOT NULL,
    name                TEXT        NOT NULL,
    description         TEXT,
    defaults_json       JSONB,
    baseline_run_id     TEXT,
    baseline_generation INTEGER     NOT NULL DEFAULT 0,
    archived_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    data                JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS eval_evaluations_owner_updated_idx
    ON app.eval_evaluations (owner_id, updated_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS eval_evaluations_agent_idx
    ON app.eval_evaluations (agent_id);

CREATE TABLE IF NOT EXISTS app.eval_dataset_revisions
(
    id             TEXT PRIMARY KEY,
    evaluation_id  TEXT        NOT NULL,
    revision       INTEGER     NOT NULL,
    status         TEXT        NOT NULL,
    manifest_json  JSONB       NOT NULL,
    content_hash   TEXT        NOT NULL,
    case_count     INTEGER     NOT NULL,
    created_by     TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    data           JSONB       NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS eval_dataset_revisions_unique_revision
    ON app.eval_dataset_revisions (evaluation_id, revision);
CREATE INDEX IF NOT EXISTS eval_dataset_revisions_evaluation_idx
    ON app.eval_dataset_revisions (evaluation_id, created_at DESC NULLS LAST);

CREATE TABLE IF NOT EXISTS app.eval_cases
(
    id                  TEXT PRIMARY KEY,
    dataset_revision_id TEXT        NOT NULL,
    case_id             TEXT        NOT NULL,
    case_hash           TEXT        NOT NULL,
    split               TEXT        NOT NULL,
    weight              DOUBLE PRECISION NOT NULL,
    body_json           JSONB       NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS eval_cases_unique_case
    ON app.eval_cases (dataset_revision_id, case_id);
CREATE INDEX IF NOT EXISTS eval_cases_split_idx
    ON app.eval_cases (dataset_revision_id, split);

CREATE TABLE IF NOT EXISTS app.eval_grader_suites
(
    id                TEXT PRIMARY KEY,
    evaluation_id     TEXT        NOT NULL,
    revision          INTEGER     NOT NULL,
    name              TEXT,
    fingerprint       TEXT        NOT NULL,
    body_json         JSONB       NOT NULL,
    test_receipt_json JSONB,
    created_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    data              JSONB       NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS eval_grader_suites_unique_revision
    ON app.eval_grader_suites (evaluation_id, revision);
CREATE INDEX IF NOT EXISTS eval_grader_suites_evaluation_idx
    ON app.eval_grader_suites (evaluation_id, created_at DESC NULLS LAST);

CREATE TABLE IF NOT EXISTS app.eval_spec_revisions
(
    id                 TEXT PRIMARY KEY,
    evaluation_id      TEXT        NOT NULL,
    revision           INTEGER     NOT NULL,
    dataset_revision_id TEXT       NOT NULL,
    grader_suite_id    TEXT        NOT NULL,
    fingerprint        TEXT        NOT NULL,
    body_json          JSONB       NOT NULL,
    created_by         TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    data               JSONB       NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS eval_spec_revisions_unique_revision
    ON app.eval_spec_revisions (evaluation_id, revision);
CREATE INDEX IF NOT EXISTS eval_spec_revisions_fingerprint_idx
    ON app.eval_spec_revisions (evaluation_id, fingerprint);

CREATE TABLE IF NOT EXISTS app.eval_candidate_snapshots
(
    id              TEXT PRIMARY KEY,
    evaluation_id   TEXT        NOT NULL,
    agent_id        TEXT        NOT NULL,
    source          TEXT        NOT NULL,
    source_revision TEXT,
    fingerprint     TEXT        NOT NULL,
    snapshot_json   JSONB       NOT NULL,
    created_by      TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    data            JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS eval_candidate_snapshots_evaluation_idx
    ON app.eval_candidate_snapshots (evaluation_id, created_at DESC NULLS LAST);
CREATE INDEX IF NOT EXISTS eval_candidate_snapshots_fingerprint_idx
    ON app.eval_candidate_snapshots (evaluation_id, fingerprint);

CREATE TABLE IF NOT EXISTS app.eval_runs
(
    id                   TEXT PRIMARY KEY,
    evaluation_id        TEXT        NOT NULL,
    actor_id             TEXT        NOT NULL,
    request_key          TEXT        NOT NULL,
    request_hash         TEXT        NOT NULL,
    spec_id              TEXT        NOT NULL,
    snapshot_id          TEXT        NOT NULL,
    purpose              TEXT        NOT NULL,
    experiment_label     TEXT,
    status               TEXT        NOT NULL,
    planned_trials       INTEGER     NOT NULL,
    started_trials       INTEGER     NOT NULL DEFAULT 0,
    succeeded_trials     INTEGER     NOT NULL DEFAULT 0,
    failed_trials        INTEGER     NOT NULL DEFAULT 0,
    unmeasured_trials    INTEGER     NOT NULL DEFAULT 0,
    cancel_requested_by  TEXT,
    cancel_requested_at  TIMESTAMPTZ,
    deadline_at          TIMESTAMPTZ NOT NULL,
    budget_json          JSONB,
    report_json          JSONB,
    error                TEXT,
    created_at           TIMESTAMPTZ NOT NULL,
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    data                 JSONB       NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS eval_runs_request_key
    ON app.eval_runs (evaluation_id, actor_id, request_key);
CREATE INDEX IF NOT EXISTS eval_runs_evaluation_created_idx
    ON app.eval_runs (evaluation_id, created_at DESC NULLS LAST, id DESC);
CREATE INDEX IF NOT EXISTS eval_runs_status_deadline_idx
    ON app.eval_runs (status, deadline_at);

CREATE TABLE IF NOT EXISTS app.eval_trials
(
    id                 TEXT PRIMARY KEY,
    run_id             TEXT        NOT NULL,
    case_id            TEXT        NOT NULL,
    case_hash          TEXT        NOT NULL,
    repetition_index   INTEGER     NOT NULL,
    split              TEXT        NOT NULL,
    weight             DOUBLE PRECISION NOT NULL,
    status             TEXT        NOT NULL,
    execution_result   TEXT,
    task_result        TEXT,
    agent_run_id       TEXT,
    owner              TEXT,
    generation         INTEGER     NOT NULL DEFAULT 0,
    lease_until        TIMESTAMPTZ,
    dispatch_token     TEXT,
    actual_output      TEXT,
    trace_json         JSONB,
    artifacts_json     JSONB,
    usage_json         JSONB,
    grading_json       JSONB,
    error              TEXT,
    stop_requested_at  TIMESTAMPTZ,
    stop_confirmed_at  TIMESTAMPTZ,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    data               JSONB       NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS eval_trials_unique_plan
    ON app.eval_trials (run_id, case_id, repetition_index);
CREATE INDEX IF NOT EXISTS eval_trials_claim_idx
    ON app.eval_trials (status, lease_until);
CREATE INDEX IF NOT EXISTS eval_trials_run_status_idx
    ON app.eval_trials (run_id, status);
CREATE INDEX IF NOT EXISTS eval_trials_run_case_idx
    ON app.eval_trials (run_id, case_id);

CREATE TABLE IF NOT EXISTS app.eval_trial_attempts
(
    id             TEXT PRIMARY KEY,
    trial_id       TEXT        NOT NULL,
    attempt_no     INTEGER     NOT NULL,
    dispatch_token TEXT,
    status         TEXT        NOT NULL,
    agent_run_id   TEXT,
    detail         TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS eval_trial_attempts_unique_attempt
    ON app.eval_trial_attempts (trial_id, attempt_no);

CREATE TABLE IF NOT EXISTS app.eval_gradings
(
    id              TEXT PRIMARY KEY,
    trial_id        TEXT        NOT NULL,
    grader_suite_id TEXT        NOT NULL,
    output_hash     TEXT        NOT NULL,
    generation      INTEGER     NOT NULL,
    status          TEXT        NOT NULL,
    results_json    JSONB       NOT NULL,
    error           TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS eval_gradings_trial_idx
    ON app.eval_gradings (trial_id, created_at DESC NULLS LAST);

CREATE TABLE IF NOT EXISTS app.eval_baseline_history
(
    id               TEXT PRIMARY KEY,
    evaluation_id    TEXT        NOT NULL,
    baseline_run_id  TEXT,
    previous_run_id  TEXT,
    generation       INTEGER     NOT NULL,
    reason           TEXT,
    actor_id         TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS eval_baseline_history_evaluation_idx
    ON app.eval_baseline_history (evaluation_id, created_at DESC NULLS LAST);

CREATE TABLE IF NOT EXISTS app.eval_audit_events
(
    id            TEXT PRIMARY KEY,
    evaluation_id TEXT        NOT NULL,
    run_id        TEXT,
    actor_id      TEXT        NOT NULL,
    event_type    TEXT        NOT NULL,
    detail        TEXT,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS eval_audit_events_evaluation_idx
    ON app.eval_audit_events (evaluation_id, created_at DESC NULLS LAST);
