package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Adds the {@code (kind, created_at)} index behind the Hub Calls list page: filtering by call kind
 * (mcp_tool / api_tool / agent) inside the mandatory time window has no index that serves both the
 * equality and the descending sort.
 *
 * @author stephen
 */
public class SchemaMigrationVHubCallKindIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260915001";
    }

    @Override
    public String description() {
        return "add hub_calls (kind, created_at) index for the hub call records list";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("hub_calls", Indexes.ascending("kind", "created_at"));
    }
}
