package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.List;

/**
 * @author stephen
 */
public class SchemaMigrationVBumpDefaultMaxTurns implements SchemaMigration {
    @Override
    public String version() {
        return "20260605003";
    }

    @Override
    public String description() {
        return "bump default assistant max_turns from 100 to 200";
    }

    @Override
    public void migrate(Mongo mongo) {
        var filter = new Document("_id", SchemaMigrationVDefaultAgent.DEFAULT_AGENT_ID);

        var update = new Document("$set", new Document()
            .append("max_turns", 200)
            .append("published_config.max_turns", 200));

        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", filter).append("u", update))));
    }
}
