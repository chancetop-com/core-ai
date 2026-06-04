package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.Arrays;
import java.util.List;

/**
 * @author Xander
 */
public class SchemaMigrationVCleanAgentIdLists implements SchemaMigration {
    @Override
    public String version() {
        return "20260604001";
    }

    @Override
    public String description() {
        return "remove null or blank ids from agent id lists";
    }

    @Override
    public void migrate(Mongo mongo) {
        var invalidIds = Arrays.asList(null, "");
        removeInvalidIds(mongo, "agents", new Document()
                .append("skill_ids", new Document("$in", invalidIds))
                .append("subagent_ids", new Document("$in", invalidIds))
                .append("published_config.skill_ids", new Document("$in", invalidIds))
                .append("published_config.subagent_ids", new Document("$in", invalidIds)));
        removeInvalidIds(mongo, "agents", new Document()
                .append("skill_ids", new Document("$regex", "^\\s*$"))
                .append("subagent_ids", new Document("$regex", "^\\s*$"))
                .append("published_config.skill_ids", new Document("$regex", "^\\s*$"))
                .append("published_config.subagent_ids", new Document("$regex", "^\\s*$")));

        removeInvalidIds(mongo, "chat_sessions", new Document()
                .append("loaded_skill_ids", new Document("$in", invalidIds))
                .append("loaded_sub_agent_ids", new Document("$in", invalidIds)));
        removeInvalidIds(mongo, "chat_sessions", new Document()
                .append("loaded_skill_ids", new Document("$regex", "^\\s*$"))
                .append("loaded_sub_agent_ids", new Document("$regex", "^\\s*$")));
    }

    private void removeInvalidIds(Mongo mongo, String collection, Document pull) {
        var updateCmd = new Document("update", collection)
                .append("updates", List.of(new Document("q", new Document())
                        .append("u", new Document("$pull", pull))
                        .append("multi", true)));
        mongo.runCommand(updateCmd);
    }
}
