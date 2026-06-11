package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * @author Xander
 */
public class SchemaMigrationVAgentRunTraceIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260611001";
    }

    @Override
    public String description() {
        return "create agent run trace index";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("agent_runs", ascending("trace_id"));
    }
}
