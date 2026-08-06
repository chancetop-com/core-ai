package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.BsonNull;
import org.bson.Document;

import java.util.List;

/**
 * Freezes dependencies in legacy published Agent snapshots that can still be validated.
 */
public class SchemaMigrationVPublishedAgentSnapshotSecurity implements SchemaMigration {
    @Override
    public String version() {
        return "20260806001";
    }

    @Override
    public String description() {
        return "validate skills and freeze system prompts in legacy published agents";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.runCommand(new Document("aggregate", "agents")
            .append("pipeline", pipeline())
            .append("cursor", new Document()));
    }

    private List<Document> pipeline() {
        return List.of(
            new Document("$match", legacyPublishedSnapshots()),
            new Document("$set", new Document("__skill_ids", new Document("$setUnion", List.of(
                new Document("$ifNull", List.of("$published_config.skill_ids", List.of())), List.of())))),
            new Document("$lookup", new Document("from", "skills")
                .append("localField", "__skill_ids")
                .append("foreignField", "_id")
                .append("as", "__validated_skills")),
            new Document("$lookup", new Document("from", "system_prompts")
                .append("localField", "published_config.system_prompt_id")
                .append("foreignField", "prompt_id")
                .append("as", "__system_prompts")),
            new Document("$set", new Document("__latest_system_prompt", latestSystemPrompt())),
            new Document("$match", new Document("$expr", dependenciesAreFreezable())),
            new Document("$set", new Document("published_config.skill_validation_version", 1)
                .append("published_config.system_prompt", frozenSystemPrompt())
                .append("published_config.system_prompt_id", "$$REMOVE")),
            new Document("$unset", List.of(
                "__skill_ids", "__validated_skills", "__system_prompts", "__latest_system_prompt")),
            new Document("$merge", new Document("into", "agents")
                .append("on", "_id")
                .append("whenMatched", "merge")
                .append("whenNotMatched", "discard"))
        );
    }

    private Document legacyPublishedSnapshots() {
        var livePromptReference = new Document("published_config.system_prompt_id",
            new Document("$exists", Boolean.TRUE).append("$nin", List.of(BsonNull.VALUE, "")));
        var unvalidatedSkills = new Document("$and", List.of(
            new Document("published_config.skill_ids", new Document("$exists", Boolean.TRUE).append("$ne", List.of())),
            new Document("published_config.skill_validation_version", new Document("$ne", 1))));
        return new Document("status", "PUBLISHED")
            .append("published_config", new Document("$exists", Boolean.TRUE).append("$ne", BsonNull.VALUE))
            .append("$or", List.of(livePromptReference, unvalidatedSkills));
    }

    private Document latestSystemPrompt() {
        var newerVersion = new Document("$gt", List.of(
            "$$this.version", new Document("$ifNull", List.of("$$value.version", -1))));
        return new Document("$reduce", new Document("input", "$__system_prompts")
            .append("initialValue", BsonNull.VALUE)
            .append("in", new Document("$cond", List.of(newerVersion, "$$this", "$$value"))));
    }

    private Document dependenciesAreFreezable() {
        var allSkillsExist = new Document("$eq", List.of(
            new Document("$size", "$__validated_skills"), new Document("$size", "$__skill_ids")));
        var noPromptReference = new Document("$eq", List.of(
            new Document("$ifNull", List.of("$published_config.system_prompt_id", "")), ""));
        var promptExists = new Document("$ne", List.of("$__latest_system_prompt", BsonNull.VALUE));
        return new Document("$and", List.of(
            allSkillsExist, new Document("$or", List.of(noPromptReference, promptExists))));
    }

    private Document frozenSystemPrompt() {
        var noPromptReference = new Document("$eq", List.of(
            new Document("$ifNull", List.of("$published_config.system_prompt_id", "")), ""));
        return new Document("$cond", List.of(
            noPromptReference, "$published_config.system_prompt", "$__latest_system_prompt.content"));
    }
}
