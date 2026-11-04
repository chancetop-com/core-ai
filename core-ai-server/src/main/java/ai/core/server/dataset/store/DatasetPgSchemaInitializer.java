package ai.core.server.dataset.store;

import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Local-test DDL: the production schema comes from flyway (core-ai-server-db-migration), this keeps the
 * integration tests runnable against a plain PostgreSQL instance. Keep it in sync with V1__dataset.sql.
 *
 * @author stephen
 */
public class DatasetPgSchemaInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetPgSchemaInitializer.class);

    private static final List<String> DDL = List.of(
            "CREATE SCHEMA IF NOT EXISTS app",
            "CREATE SCHEMA IF NOT EXISTS dataset",
            "CREATE TABLE IF NOT EXISTS app.datasets ("
                    + " id text PRIMARY KEY, name text NOT NULL, description text, user_id text NOT NULL, type text,"
                    + " schema_json text NOT NULL DEFAULT '[]', schema_version integer NOT NULL DEFAULT 1,"
                    + " created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL)",
            "CREATE INDEX IF NOT EXISTS ix_datasets_user ON app.datasets (user_id, updated_at DESC)",
            "CREATE TABLE IF NOT EXISTS app.dataset_views ("
                    + " dataset_id text PRIMARY KEY, view_name text NOT NULL UNIQUE, column_map jsonb NOT NULL,"
                    + " schema_hash text NOT NULL, updated_at timestamptz NOT NULL)",
            "CREATE TABLE IF NOT EXISTS app.dataset_field_indexes ("
                    + " dataset_id text NOT NULL, field_name text NOT NULL, index_name text NOT NULL UNIQUE,"
                    + " reason text NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY (dataset_id, field_name))",
            "CREATE TABLE IF NOT EXISTS dataset.dataset_records ("
                    + " id text PRIMARY KEY, dataset_id text NOT NULL, agent_id text NOT NULL, run_id text, session_id text,"
                    + " user_id text, data jsonb NOT NULL DEFAULT '{}'::jsonb, run_started_at timestamptz NOT NULL,"
                    + " created_at timestamptz NOT NULL, created_by text NOT NULL, updated_at timestamptz NOT NULL,"
                    + " updated_by text NOT NULL)",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_dataset_time ON dataset.dataset_records (dataset_id, run_started_at DESC)",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_dataset_agent_time ON dataset.dataset_records (dataset_id, agent_id, run_started_at DESC)",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_updated ON dataset.dataset_records (updated_at)",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_run ON dataset.dataset_records (run_id) WHERE run_id IS NOT NULL",
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_dataset_records_session ON dataset.dataset_records (dataset_id, session_id) WHERE session_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_data ON dataset.dataset_records USING gin (data jsonb_path_ops)",
            "CREATE TABLE IF NOT EXISTS dataset.dataset_sql_grants ("
                    + " token text NOT NULL, dataset_id text NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY (token, dataset_id))",
            "CREATE OR REPLACE FUNCTION dataset.jsonb_merge_patch(target jsonb, patch jsonb) RETURNS jsonb "
                    + "LANGUAGE plpgsql IMMUTABLE AS $$ "
                    + "DECLARE result jsonb; k text; v jsonb; "
                    + "BEGIN "
                    + " IF patch IS NULL OR jsonb_typeof(patch) <> 'object' THEN RETURN patch; END IF; "
                    + " IF target IS NULL OR jsonb_typeof(target) <> 'object' THEN result := '{}'::jsonb; ELSE result := target; END IF; "
                    + " FOR k, v IN SELECT key, value FROM jsonb_each(patch) LOOP "
                    + "  IF jsonb_typeof(v) = 'null' THEN result := result - k; "
                    + "  ELSIF jsonb_typeof(v) = 'object' THEN result := jsonb_set(result, ARRAY[k], dataset.jsonb_merge_patch(result -> k, v)); "
                    + "  ELSE result := jsonb_set(result, ARRAY[k], v); "
                    + "  END IF; "
                    + " END LOOP; "
                    + " RETURN result; "
                    + "END $$");

    @Inject
    PgDdl ddl;

    public void initialize() {
        ddl.executeAll(DDL);
        LOGGER.info("dataset postgres schema ready");
    }
}
