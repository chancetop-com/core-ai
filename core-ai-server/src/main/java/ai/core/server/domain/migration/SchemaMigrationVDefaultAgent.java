package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * @author stephen
 */
public class SchemaMigrationVDefaultAgent implements SchemaMigration {
    public static final String DEFAULT_AGENT_ID = "default-assistant";

    @Override
    public String version() {
        return "20260306002";
    }

    @Override
    public String description() {
        return "create default assistant agent";
    }

    @Override
    public void migrate(Mongo mongo) {
        var now = Date.from(Instant.now());
        var publishedConfig = new Document()
            .append("system_prompt", "You are a helpful AI assistant.")
            .append("tool_ids", List.of("builtin-all"))
            .append("max_turns", 100)
            .append("timeout_seconds", 600);

        var agent = new Document()
            .append("_id", DEFAULT_AGENT_ID)
            .append("user_id", "system")
            .append("name", "Assistant")
            .append("description", "Default assistant with all builtin tools")
            .append("system_prompt", "You are a helpful AI assistant.")
            .append("tool_ids", List.of("builtin-all"))
            .append("max_turns", 100)
            .append("timeout_seconds", 600)
            .append("system_default", true)
            .append("type", "AGENT")
            .append("status", "PUBLISHED")
            .append("published_config", publishedConfig)
            .append("published_at", now)
            .append("created_at", now)
            .append("updated_at", now);

        var filter = new Document("_id", DEFAULT_AGENT_ID);
        var update = new Document("$setOnInsert", agent);
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter).append("u", update).append("upsert", true))));
    }
}
