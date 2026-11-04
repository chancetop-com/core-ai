package ai.core.server.dataset.store;

import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Idempotent DDL for the PoC tables and the merge function. A production rollout would move this into
 * the existing flyway migration module; the PoC keeps it in-process so a fresh local database works
 * without extra tooling.
 *
 * @author stephen
 */
public class DatasetPgSchemaInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetPgSchemaInitializer.class);

    private static final List<String> DDL = List.of(
            "CREATE TABLE IF NOT EXISTS dataset_records ("
                    + " id text PRIMARY KEY,"
                    + " dataset_id text NOT NULL,"
                    + " agent_id text NOT NULL,"
                    + " run_id text,"
                    + " session_id text,"
                    + " user_id text,"
                    + " data jsonb NOT NULL DEFAULT '{}'::jsonb,"
                    + " run_started_at timestamptz NOT NULL,"
                    + " created_at timestamptz NOT NULL,"
                    + " created_by text NOT NULL,"
                    + " updated_at timestamptz NOT NULL,"
                    + " updated_by text NOT NULL)",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_dataset_time ON dataset_records (dataset_id, run_started_at DESC)",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_dataset_agent_time ON dataset_records (dataset_id, agent_id, run_started_at DESC)",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_user ON dataset_records (user_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_dataset_records_session ON dataset_records (dataset_id, session_id) WHERE session_id IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS ix_dataset_records_data ON dataset_records USING gin (data jsonb_path_ops)",
            "CREATE TABLE IF NOT EXISTS dataset_views ("
                    + " dataset_id text PRIMARY KEY,"
                    + " view_name text NOT NULL UNIQUE,"
                    + " column_map jsonb NOT NULL,"
                    + " schema_hash text NOT NULL,"
                    + " updated_at timestamptz NOT NULL)",
            "CREATE OR REPLACE FUNCTION jsonb_merge_patch(target jsonb, patch jsonb) RETURNS jsonb "
                    + "LANGUAGE plpgsql IMMUTABLE AS $$ "
                    + "DECLARE result jsonb; k text; v jsonb; "
                    + "BEGIN "
                    + " IF patch IS NULL OR jsonb_typeof(patch) <> 'object' THEN RETURN patch; END IF; "
                    + " IF target IS NULL OR jsonb_typeof(target) <> 'object' THEN result := '{}'::jsonb; ELSE result := target; END IF; "
                    + " FOR k, v IN SELECT key, value FROM jsonb_each(patch) LOOP "
                    + "  IF jsonb_typeof(v) = 'null' THEN result := result - k; "
                    + "  ELSIF jsonb_typeof(v) = 'object' THEN result := jsonb_set(result, ARRAY[k], jsonb_merge_patch(result -> k, v)); "
                    + "  ELSE result := jsonb_set(result, ARRAY[k], v); "
                    + "  END IF; "
                    + " END LOOP; "
                    + " RETURN result; "
                    + "END $$",
            "CREATE TABLE IF NOT EXISTS dataset_sql_grants (token text NOT NULL, dataset_id text NOT NULL,"
                    + " created_at timestamptz NOT NULL, PRIMARY KEY (token, dataset_id))");

    @Inject
    PgDdl ddl;

    public void initialize() {
        ddl.executeAll(DDL);
        LOGGER.info("dataset postgres schema ready");
    }
}
