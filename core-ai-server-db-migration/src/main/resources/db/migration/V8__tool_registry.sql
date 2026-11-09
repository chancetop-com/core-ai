CREATE TABLE IF NOT EXISTS app.tool_registry (
    id text PRIMARY KEY,
    data jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS tool_registry_type_name_idx ON app.tool_registry ((data ->> 'type'), (data ->> 'name'));
