package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

import java.util.concurrent.TimeUnit;

/**
 * mcp_hub_calls audit indexes: 90-day TTL plus lookup paths for per-user and per-tool queries.
 *
 * @author stephen
 */
public class SchemaMigrationVMcpHubCallsIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260904001";
    }

    @Override
    public String description() {
        return "add TTL and lookup indexes on mcp_hub_calls";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("mcp_hub_calls", Indexes.ascending("created_at"),
            new IndexOptions().expireAfter(90L, TimeUnit.DAYS));
        mongo.createIndex("mcp_hub_calls", Indexes.ascending("user_id", "created_at"));
        mongo.createIndex("mcp_hub_calls", Indexes.ascending("server_name", "tool_name", "created_at"));
    }
}
