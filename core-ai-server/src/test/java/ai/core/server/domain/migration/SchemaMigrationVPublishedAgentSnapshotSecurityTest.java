package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SchemaMigrationVPublishedAgentSnapshotSecurityTest {
    @Test
    void migratesOnlyPublishedSnapshotsWhoseLegacyDependenciesCanBeFrozen() {
        var mongo = mock(Mongo.class);

        new SchemaMigrationVPublishedAgentSnapshotSecurity().migrate(mongo);

        var command = ArgumentCaptor.forClass(org.bson.conversions.Bson.class);
        verify(mongo).runCommand(command.capture());
        var aggregate = (Document) command.getValue();
        assertEquals("agents", aggregate.getString("aggregate"));
        assertEquals(new Document(), aggregate.get("cursor"));

        var pipeline = aggregate.getList("pipeline", Document.class);
        assertEquals(9, pipeline.size());
        assertEquals("PUBLISHED", pipeline.get(0).get("$match", Document.class).getString("status"));

        var skillLookup = pipeline.get(2).get("$lookup", Document.class);
        assertEquals("skills", skillLookup.getString("from"));
        assertEquals("__skill_ids", skillLookup.getString("localField"));
        assertEquals("_id", skillLookup.getString("foreignField"));
        assertEquals("__validated_skills", skillLookup.getString("as"));

        var promptLookup = pipeline.get(3).get("$lookup", Document.class);
        assertEquals("system_prompts", promptLookup.getString("from"));
        assertEquals("published_config.system_prompt_id", promptLookup.getString("localField"));
        assertEquals("prompt_id", promptLookup.getString("foreignField"));
        assertEquals("__system_prompts", promptLookup.getString("as"));

        var latestPrompt = pipeline.get(4).get("$set", Document.class)
            .get("__latest_system_prompt", Document.class);
        assertEquals("$__system_prompts", latestPrompt.get("$reduce", Document.class).getString("input"));

        var eligibility = pipeline.get(5).get("$match", Document.class).get("$expr", Document.class);
        assertEquals(2, eligibility.getList("$and", Document.class).size());

        var frozen = pipeline.get(6).get("$set", Document.class);
        assertEquals(1, frozen.getInteger("published_config.skill_validation_version"));
        assertEquals("$$REMOVE", frozen.getString("published_config.system_prompt_id"));

        assertEquals(List.of("__skill_ids", "__validated_skills", "__system_prompts", "__latest_system_prompt"),
            pipeline.get(7).getList("$unset", String.class));
        var merge = pipeline.get(8).get("$merge", Document.class);
        assertEquals("agents", merge.getString("into"));
        assertEquals("merge", merge.getString("whenMatched"));
        assertEquals("discard", merge.getString("whenNotMatched"));
        assertTrue(aggregate.containsKey("cursor"));
    }
}
