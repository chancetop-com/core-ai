package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import core.framework.mongo.Mongo;
import org.bson.Document;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.descending;

/**
 * @author stephen
 */
public class SchemaMigrationVInitialize implements SchemaMigration {
    @Override
    public String version() {
        return "20260306003";
    }

    @Override
    public String description() {
        return "create initial indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.runAdminCommand(new Document().append("setParameter", 1).append("notablescan", 1));

        mongo.createIndex("agents", ascending("user_id"));
        mongo.createIndex("agents", ascending("system_default"));
        mongo.createIndex("agents", ascending("created_at"));
        mongo.createIndex("agents", ascending("updated_at"));
        mongo.createIndex("agents", ascending("status"));

        mongo.createIndex("agent_runs", ascending("agent_id"));
        mongo.createIndex("agent_runs", ascending("agent_id", "status"));
        mongo.createIndex("agent_runs", descending("started_at"));

        mongo.createIndex("agent_schedules", ascending("agent_id"));
        mongo.createIndex("agent_schedules", ascending("enabled", "next_run_at"));

        mongo.createIndex("users", ascending("api_key"),
            new IndexOptions().unique(true).sparse(true));

        mongo.createIndex("tool_registry", ascending("category"));
        mongo.createIndex("tool_registry", ascending("enabled"));

        mongo.createIndex("file_records", ascending("user_id"));
        mongo.createIndex("file_records", ascending("created_at"));

        mongo.createIndex("agents", ascending("user_id", "system_default"));
        mongo.createIndex("agents", ascending("tool_ids"));
        mongo.createIndex("agents", ascending("tools"));
    }
}
