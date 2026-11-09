CREATE TABLE IF NOT EXISTS app.projects (
    id text PRIMARY KEY,
    user_id text NOT NULL,
    status text NOT NULL,
    stats_dirty boolean,
    analysis_status text,
    last_analyzed_at timestamptz,
    last_analysis_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    archived_at timestamptz,
    data jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS projects_user_created_idx ON app.projects (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS projects_status_created_idx ON app.projects (status, created_at DESC);
CREATE INDEX IF NOT EXISTS projects_status_stats_dirty_idx ON app.projects (status, stats_dirty);
CREATE INDEX IF NOT EXISTS projects_status_analysis_last_analyzed_idx ON app.projects (status, analysis_status, last_analyzed_at);
CREATE INDEX IF NOT EXISTS projects_status_analysis_last_analysis_idx ON app.projects (status, analysis_status, last_analysis_at);
