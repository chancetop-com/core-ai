CREATE TABLE IF NOT EXISTS app.system_prompts (
    id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS system_prompts_prompt_version_idx
    ON app.system_prompts ((data ->> 'promptId'), ((data ->> 'version')::int) DESC);

CREATE INDEX IF NOT EXISTS system_prompts_name_version_idx
    ON app.system_prompts ((data ->> 'name'), ((data ->> 'version')::int) DESC);

CREATE TABLE IF NOT EXISTS app.prompt_templates (
    id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS prompt_templates_updated_idx ON app.prompt_templates (updated_at DESC);

CREATE TABLE IF NOT EXISTS app.triggers (
    id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS triggers_type_idx ON app.triggers ((data ->> 'type'));

CREATE TABLE IF NOT EXISTS app.service_api (
    id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS service_api_name_idx ON app.service_api ((data ->> 'name'));
