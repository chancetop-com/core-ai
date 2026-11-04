-- dataset storage on PostgreSQL (stage 1)
-- owner: core_ai (the CNPG bootstrap owner is also the application role);
-- dataset_reader is provisioned by ops, flyway only grants and wires RLS.

CREATE SCHEMA IF NOT EXISTS app;
CREATE SCHEMA IF NOT EXISTS dataset;

-- ---------------------------------------------------------------------------
-- metadata
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS app.datasets (
    id             text PRIMARY KEY,
    name           text NOT NULL,
    description    text,
    user_id        text NOT NULL,
    type           text,                          -- null = GENERAL
    schema_json    text NOT NULL DEFAULT '[]',    -- List<SchemaField> serialized by JsonUtil
    schema_version integer NOT NULL DEFAULT 1,    -- +1 on every schema change
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_datasets_user ON app.datasets (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS app.dataset_views (
    dataset_id  text PRIMARY KEY,
    view_name   text NOT NULL UNIQUE,
    column_map  jsonb NOT NULL,
    schema_hash text NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS app.dataset_field_indexes (
    dataset_id  text NOT NULL,
    field_name  text NOT NULL,
    index_name  text NOT NULL UNIQUE,
    reason      text NOT NULL,                    -- REPORT | MANUAL
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (dataset_id, field_name)
);

-- ---------------------------------------------------------------------------
-- records
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS dataset.dataset_records (
    id             text PRIMARY KEY,
    dataset_id     text NOT NULL,
    agent_id       text NOT NULL,
    run_id         text,
    session_id     text,
    user_id        text,
    data           jsonb NOT NULL DEFAULT '{}'::jsonb,
    run_started_at timestamptz NOT NULL,
    created_at     timestamptz NOT NULL,
    created_by     text NOT NULL,
    updated_at     timestamptz NOT NULL,
    updated_by     text NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_dataset_records_dataset_time ON dataset.dataset_records (dataset_id, run_started_at DESC);
CREATE INDEX IF NOT EXISTS ix_dataset_records_dataset_agent_time ON dataset.dataset_records (dataset_id, agent_id, run_started_at DESC);
CREATE INDEX IF NOT EXISTS ix_dataset_records_updated ON dataset.dataset_records (updated_at);
CREATE INDEX IF NOT EXISTS ix_dataset_records_run ON dataset.dataset_records (run_id) WHERE run_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_dataset_records_session ON dataset.dataset_records (dataset_id, session_id) WHERE session_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_dataset_records_data ON dataset.dataset_records USING gin (data jsonb_path_ops);

CREATE TABLE IF NOT EXISTS dataset.dataset_sql_grants (
    token      text NOT NULL,
    dataset_id text NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (token, dataset_id)
);

-- ---------------------------------------------------------------------------
-- functions
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION dataset.jsonb_merge_patch(target jsonb, patch jsonb) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE result jsonb; k text; v jsonb;
BEGIN
    IF patch IS NULL OR jsonb_typeof(patch) <> 'object' THEN RETURN patch; END IF;
    IF target IS NULL OR jsonb_typeof(target) <> 'object' THEN result := '{}'::jsonb; ELSE result := target; END IF;
    FOR k, v IN SELECT key, value FROM jsonb_each(patch) LOOP
        IF jsonb_typeof(v) = 'null' THEN result := result - k;
        ELSIF jsonb_typeof(v) = 'object' THEN result := jsonb_set(result, ARRAY[k], dataset.jsonb_merge_patch(result -> k, v));
        ELSE result := jsonb_set(result, ARRAY[k], v);
        END IF;
    END LOOP;
    RETURN result;
END $$;

CREATE OR REPLACE FUNCTION dataset.dataset_to_date(v jsonb) RETURNS date
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF jsonb_typeof(v) <> 'string' OR NOT (v #>> '{}') ~ '^\d{4}-\d{2}-\d{2}' THEN RETURN NULL; END IF;
    RETURN make_date(substr(v #>> '{}', 1, 4)::int, substr(v #>> '{}', 6, 2)::int, substr(v #>> '{}', 9, 2)::int);
EXCEPTION WHEN others THEN RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION dataset.dataset_to_timestamp(v jsonb) RETURNS timestamptz
LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    IF jsonb_typeof(v) <> 'string' OR NOT (v #>> '{}') ~ '(Z|[+-]\d{2}:?\d{2})$' THEN RETURN NULL; END IF;
    RETURN (v #>> '{}')::timestamptz;
EXCEPTION WHEN others THEN RETURN NULL;
END $$;

-- ---------------------------------------------------------------------------
-- application role
-- core_ai is the owner in CNPG, so it already has DDL/DML; RLS is bypassed for it.
-- ---------------------------------------------------------------------------

ALTER ROLE core_ai SET search_path = app, dataset;

-- ---------------------------------------------------------------------------
-- reader role: SELECT only, scoped by RLS to the token's datasets
-- ---------------------------------------------------------------------------

GRANT USAGE ON SCHEMA dataset TO dataset_reader;
GRANT SELECT ON dataset.dataset_records, dataset.dataset_sql_grants TO dataset_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA dataset GRANT SELECT ON TABLES TO dataset_reader;
-- dataset_reader's search_path is set per session by DatasetSqlExecutor (SET LOCAL search_path = dataset);
-- changing another role's default requires CREATEROLE, which the migration role does not have.

ALTER TABLE dataset.dataset_records ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS reader_scope ON dataset.dataset_records;
CREATE POLICY reader_scope ON dataset.dataset_records FOR SELECT TO dataset_reader
    USING (dataset_id IN (SELECT g.dataset_id FROM dataset.dataset_sql_grants g
                          WHERE g.token = current_setting('app.dataset_token', true)));

ALTER TABLE dataset.dataset_sql_grants ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS reader_scope ON dataset.dataset_sql_grants;
CREATE POLICY reader_scope ON dataset.dataset_sql_grants FOR SELECT TO dataset_reader
    USING (token = current_setting('app.dataset_token', true));
