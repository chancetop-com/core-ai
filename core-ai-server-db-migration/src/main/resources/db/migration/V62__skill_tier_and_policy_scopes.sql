CREATE INDEX IF NOT EXISTS idx_skills_tier ON app.skills ((data ->> 'tier'));

CREATE INDEX IF NOT EXISTS skill_quality_policy_revisions_scope_rev_idx
    ON app.skill_quality_policy_revisions ((data ->> 'scope'), ((data ->> 'revision')::int) DESC);
