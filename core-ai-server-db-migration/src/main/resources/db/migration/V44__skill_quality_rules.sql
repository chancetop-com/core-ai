CREATE TABLE IF NOT EXISTS app.skill_quality_rules
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS skill_quality_rules_key_idx ON app.skill_quality_rules ((data ->> 'ruleKey'));

CREATE TABLE IF NOT EXISTS app.skill_quality_rule_revisions
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS skill_quality_rule_revisions_rule_rev_idx
    ON app.skill_quality_rule_revisions ((data ->> 'ruleId'), ((data ->> 'revision')::int));

CREATE TABLE IF NOT EXISTS app.skill_quality_policy_revisions
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS skill_quality_policy_revisions_rev_idx
    ON app.skill_quality_policy_revisions (((data ->> 'revision')::int));

CREATE TABLE IF NOT EXISTS app.skill_quality_policy_heads
(
    id         TEXT PRIMARY KEY,
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS skill_quality_policy_heads_scope_idx ON app.skill_quality_policy_heads ((data ->> 'scope'));
