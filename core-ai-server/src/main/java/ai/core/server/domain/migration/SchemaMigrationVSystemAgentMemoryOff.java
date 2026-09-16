package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.List;

/**
 * Memory is opt-out, so every shared system agent (default-assistant, agent-builder, the builtin project
 * agents) was quietly consolidating the memories of all its callers into one agent-scoped bucket. Personal
 * assistants fork per user and keep memory on; the shared templates must stay off.
 *
 * @author Xander
 */
public class SchemaMigrationVSystemAgentMemoryOff implements SchemaMigration {
    private static final Document SYSTEM_AGENT = new Document("system_default", Boolean.TRUE);

    @Override
    public String version() {
        return "20260916001";
    }

    @Override
    public String description() {
        return "disable memory on shared system agents (opt-out default leaked callers into one bucket)";
    }

    @Override
    public void migrate(Mongo mongo) {
        setMemoryOff(mongo, SYSTEM_AGENT, new Document("enable_memory", Boolean.FALSE));
        // $set on a nested path would create a bogus published_config for an unpublished agent and make it
        // look published, so only documents that already carry one are touched
        setMemoryOff(mongo, new Document(SYSTEM_AGENT).append("published_config", new Document("$type", "object")),
            new Document("published_config.enable_memory", Boolean.FALSE));
    }

    private void setMemoryOff(Mongo mongo, Document filter, Document fields) {
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter)
                .append("u", new Document("$set", fields))
                .append("multi", Boolean.TRUE))));
    }
}
