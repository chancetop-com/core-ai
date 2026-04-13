package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class SchemaMigrationVMigrateToolIdsToTools implements SchemaMigration {

    @Override
    public String version() {
        return "20260306004";
    }

    @Override
    public String description() {
        return "migrate tool_ids to tools in agents collection";
    }

    @Override
    public void migrate(Mongo mongo) {
        // Find all agents that have tool_ids but no tools field, and migrate them
        var filter = new Document("$and", List.of(
                new Document("tool_ids", new Document("$exists", true)),
                new Document("tools", new Document("$exists", false))
        ));

        var findCmd = new Document("find", "agents")
                .append("filter", filter)
                .append("hint", new Document("$natural", 1));

        var result = mongo.runCommand(findCmd);
        var cursor = result.get("cursor", Document.class);
        if (cursor == null) return;

        var documents = cursor.getList("firstBatch", Document.class);
        if (documents == null || documents.isEmpty()) return;

        for (var agent : documents) {
            migrateAgent(mongo, agent);
        }
    }

    private void migrateAgent(Mongo mongo, Document agent) {
        var id = agent.getString("_id");
        var toolIds = agent.getList("tool_ids", String.class);

        if (toolIds == null || toolIds.isEmpty()) {
            return;
        }

        var tools = new ArrayList<Document>();
        for (var toolId : toolIds) {
            tools.add(toolIdToToolRef(toolId));
        }

        var updateFields = new Document("tools", tools);

        // Also migrate published_config.tool_ids if present
        var publishedConfig = agent.get("published_config", Document.class);
        if (publishedConfig != null) {
            var publishedToolIds = publishedConfig.getList("tool_ids", String.class);
            if (publishedToolIds != null && !publishedToolIds.isEmpty()) {
                var publishedTools = new ArrayList<Document>();
                for (var toolId : publishedToolIds) {
                    publishedTools.add(toolIdToToolRef(toolId));
                }
                updateFields.append("published_config.tools", publishedTools);
            }
        }

        var filter = new Document("_id", id);
        var updateCmd = new Document("update", "agents")
                .append("updates", List.of(
                        new Document("q", filter)
                                .append("u", new Document("$set", updateFields))
                ));

        mongo.runCommand(updateCmd);
    }

    private Document toolIdToToolRef(String toolId) {
        var doc = new Document("id", toolId);

        if (toolId.startsWith("builtin-")) {
            doc.append("type", "BUILTIN");
        } else if (toolId.startsWith("config:")) {
            doc.append("type", "MCP");
            doc.append("source", toolId.substring("config:".length()));
        } else if (toolId.startsWith("api-app:") || "builtin-service-api".equals(toolId)) {
            doc.append("type", "API");
        }

        return doc;
    }
}
