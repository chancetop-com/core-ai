package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.Arrays;
import java.util.List;

/**
 * @author Xander
 */
public class SchemaMigrationVCleanAgentIdListsWithPipeline implements SchemaMigration {
    @Override
    public String version() {
        return "20260604002";
    }

    @Override
    public String description() {
        return "rewrite agent id lists without null or blank values";
    }

    @Override
    public void migrate(Mongo mongo) {
        cleanArrayField(mongo, "agents", "skill_ids");
        cleanArrayField(mongo, "agents", "subagent_ids");
        cleanArrayField(mongo, "agents", "published_config.skill_ids");
        cleanArrayField(mongo, "agents", "published_config.subagent_ids");
        cleanArrayField(mongo, "chat_sessions", "loaded_skill_ids");
        cleanArrayField(mongo, "chat_sessions", "loaded_sub_agent_ids");
    }

    private void cleanArrayField(Mongo mongo, String collection, String field) {
        var updateCmd = new Document("update", collection)
                .append("updates", List.of(new Document("q", new Document(field, new Document("$type", "array")))
                        .append("u", List.of(new Document("$set", new Document(field, cleanExpression(field)))))
                        .append("multi", true)));
        mongo.runCommand(updateCmd);
    }

    private Document cleanExpression(String field) {
        return new Document("$filter", new Document("input", "$" + field)
                .append("as", "id")
                .append("cond", validIdCondition()));
    }

    private Document validIdCondition() {
        var stringValue = new Document("$convert", new Document("input", "$$id")
                .append("to", "string")
                .append("onError", "")
                .append("onNull", ""));
        var trimmed = new Document("$trim", new Document("input", stringValue));
        return new Document("$and", Arrays.asList(
                new Document("$ne", Arrays.asList("$$id", null)),
                new Document("$ne", List.of(trimmed, ""))));
    }
}
