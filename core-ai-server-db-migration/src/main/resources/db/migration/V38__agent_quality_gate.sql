CREATE TABLE IF NOT EXISTS app.agent_quality_rules
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_quality_rules_key_idx ON app.agent_quality_rules ((data ->> 'ruleKey'));

CREATE TABLE IF NOT EXISTS app.agent_quality_rule_revisions
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_quality_rule_revisions_rule_rev_idx
    ON app.agent_quality_rule_revisions ((data ->> 'ruleId'), ((data ->> 'revision')::int));

CREATE TABLE IF NOT EXISTS app.agent_quality_policy_revisions
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_quality_policy_revisions_rev_idx
    ON app.agent_quality_policy_revisions (((data ->> 'revision')::int));

CREATE TABLE IF NOT EXISTS app.agent_quality_policy_heads
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_quality_policy_heads_scope_idx ON app.agent_quality_policy_heads ((data ->> 'scope'));

CREATE TABLE IF NOT EXISTS app.agent_quality_runs
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS agent_quality_runs_agent_created_idx
    ON app.agent_quality_runs ((data ->> 'agentId'), (data ->> 'createdAt') DESC);
CREATE UNIQUE INDEX IF NOT EXISTS agent_quality_runs_operation_key_idx
    ON app.agent_quality_runs ((data ->> 'operationKeyHash'))
    WHERE data ->> 'operationKeyHash' IS NOT NULL;

CREATE TABLE IF NOT EXISTS app.agent_publication_records
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS agent_publication_records_agent_published_idx
    ON app.agent_publication_records ((data ->> 'agentId'), (data ->> 'publishedAt') DESC);
CREATE UNIQUE INDEX IF NOT EXISTS agent_publication_records_operation_key_idx
    ON app.agent_publication_records ((data ->> 'operationKeyHash'))
    WHERE data ->> 'operationKeyHash' IS NOT NULL;

-- Existing published agents get a metadata snapshot of their current catalog values, so a later update of
-- the editable name/description cannot silently change the catalog-visible metadata without a publish.
UPDATE app.agents
SET data = jsonb_set(
        jsonb_set(data, '{publishedName}', to_jsonb(COALESCE(data ->> 'name', ''))),
        '{publishedDescription}', to_jsonb(COALESCE(data ->> 'description', '')))
WHERE data IS NOT NULL
  AND data ->> 'status' = 'PUBLISHED'
  AND NOT (data ? 'publishedName');

