CREATE TABLE IF NOT EXISTS app.skills
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_skills_qualified_name ON app.skills ((data ->> 'qualified_name'));
CREATE INDEX IF NOT EXISTS idx_skills_namespace ON app.skills ((data ->> 'namespace'));
CREATE INDEX IF NOT EXISTS idx_skills_source_type ON app.skills ((data ->> 'source_type'));
CREATE INDEX IF NOT EXISTS idx_skills_name ON app.skills ((data ->> 'name'));
CREATE INDEX IF NOT EXISTS idx_skills_updated_at ON app.skills ((data ->> 'updated_at') DESC);

CREATE TABLE IF NOT EXISTS app.marketplace_repos
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_marketplace_repos_name ON app.marketplace_repos ((data ->> 'name'));
