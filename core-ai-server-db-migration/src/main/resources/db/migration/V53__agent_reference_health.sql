CREATE TABLE IF NOT EXISTS app.agent_reference_issues
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_reference_issues_active_idx
    ON app.agent_reference_issues ((data ->> 'agentId'), (data ->> 'refType'), (data ->> 'refId'))
    WHERE data ->> 'resolvedAt' IS NULL;

CREATE INDEX IF NOT EXISTS agent_reference_issues_agent_idx
    ON app.agent_reference_issues ((data ->> 'agentId'), (data ->> 'resolvedAt'));

CREATE INDEX IF NOT EXISTS agent_reference_issues_ref_idx
    ON app.agent_reference_issues ((data ->> 'refType'), (data ->> 'refId'), (data ->> 'resolvedAt'));

CREATE TABLE IF NOT EXISTS app.agent_health_state
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE app.notifications ADD COLUMN IF NOT EXISTS dedupe_key TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS notifications_dedupe_key_idx
    ON app.notifications (dedupe_key)
    WHERE dedupe_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS agents_skill_ids_gin_idx
    ON app.agents USING GIN ((data -> 'skillIds'));

CREATE INDEX IF NOT EXISTS agents_published_skill_ids_gin_idx
    ON app.agents USING GIN ((data -> 'publishedConfig' -> 'skillIds'));

CREATE INDEX IF NOT EXISTS agents_tools_gin_idx
    ON app.agents USING GIN ((data -> 'tools'));

CREATE INDEX IF NOT EXISTS agents_published_tools_gin_idx
    ON app.agents USING GIN ((data -> 'publishedConfig' -> 'tools'));

CREATE INDEX IF NOT EXISTS agent_quality_runs_agent_finished_idx
    ON app.agent_quality_runs ((data ->> 'agentId'), (data ->> 'finishedAt') DESC);
