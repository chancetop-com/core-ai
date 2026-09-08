package ai.core.server.domain.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generalizes the MCP hub audit collection into {@code hub_calls}: copies existing
 * {@code mcp_hub_calls} rows into the new shape (kind/group/name/target set from the old
 * server/tool fields, legacy columns dropped) and moves the TTL/lookup indexes over.
 * Fresh environments have no legacy collection and only get the new indexes.
 *
 * @author stephen
 */
public class SchemaMigrationVHubCalls implements SchemaMigration {
    @Override
    public String version() {
        return "20260908001";
    }

    @Override
    public String description() {
        return "generalize mcp_hub_calls audit rows into hub_calls (kind/group/name/target)";
    }

    @Override
    public void migrate(Mongo mongo) {
        // $out copy (same database, no db-name needed); a renameCollection admin command would
        // require the database name which migrations cannot see
        var copy = new Document("aggregate", "mcp_hub_calls").append("pipeline", List.of(
                        new Document("$set", new Document("kind", "mcp_tool")
                                .append("group", "$server_name")
                                .append("name", "$tool_name")
                                .append("target", new Document("$concat", List.of("$server_name", "/", "$tool_name")))),
                        new Document("$unset", List.of("server_id", "server_name", "tool_name")),
                        new Document("$out", "hub_calls")))
                .append("cursor", new Document());
        try {
            mongo.runCommand(copy);
        } catch (MongoCommandException e) {
            // NamespaceNotFound on fresh environments: nothing to migrate
            if (!"NamespaceNotFound".equals(e.getErrorCodeName()) && e.getErrorCode() != 26) throw e;
        }
        mongo.dropCollection("mcp_hub_calls");
        mongo.createIndex("hub_calls", Indexes.ascending("created_at"),
                new IndexOptions().expireAfter(90L, TimeUnit.DAYS));
        mongo.createIndex("hub_calls", Indexes.ascending("user_id", "created_at"));
        mongo.createIndex("hub_calls", Indexes.ascending("kind", "group", "name", "created_at"));
    }
}
