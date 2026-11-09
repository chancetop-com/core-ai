CREATE TABLE IF NOT EXISTS app.project_subjects
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_project_subjects_project ON app.project_subjects ((data ->> 'projectId'));
CREATE INDEX IF NOT EXISTS idx_project_subjects_status ON app.project_subjects ((data ->> 'status'));
CREATE INDEX IF NOT EXISTS idx_project_subjects_report_run ON app.project_subjects ((data ->> 'reportRunId'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_project_subjects_auto_name
    ON app.project_subjects ((data ->> 'projectId'), (data ->> 'nameKey'))
    WHERE (data ->> 'source') = 'auto';

CREATE TABLE IF NOT EXISTS app.project_subject_attributions
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_project_attributions_project ON app.project_subject_attributions ((data ->> 'projectId'));
CREATE INDEX IF NOT EXISTS idx_project_attributions_subject ON app.project_subject_attributions ((data ->> 'subjectId'));
CREATE INDEX IF NOT EXISTS idx_project_attributions_target ON app.project_subject_attributions ((data ->> 'targetType'), (data ->> 'targetId'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_project_attributions_subject_target
    ON app.project_subject_attributions ((data ->> 'subjectId'), (data ->> 'targetType'), (data ->> 'targetId'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_project_attributions_file_home
    ON app.project_subject_attributions ((data ->> 'projectId'), (data ->> 'targetId'))
    WHERE (data ->> 'targetType') = 'file';

CREATE TABLE IF NOT EXISTS app.project_subject_events
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_project_events_project ON app.project_subject_events ((data ->> 'projectId'), (data ->> 'at'));
CREATE INDEX IF NOT EXISTS idx_project_events_subject ON app.project_subject_events ((data ->> 'subjectId'), (data ->> 'at'));
CREATE INDEX IF NOT EXISTS idx_project_events_type ON app.project_subject_events ((data ->> 'subjectId'), (data ->> 'type'), (data ->> 'at'));

CREATE TABLE IF NOT EXISTS app.project_target_scans
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_project_target_scans_target
    ON app.project_target_scans ((data ->> 'projectId'), (data ->> 'targetType'), (data ->> 'targetId'));

CREATE TABLE IF NOT EXISTS app.project_report_drafts
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_project_report_drafts_subject ON app.project_report_drafts ((data ->> 'subjectId'));

CREATE TABLE IF NOT EXISTS app.project_stats
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
