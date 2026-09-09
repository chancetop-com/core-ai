package ai.core.server.domain.migration;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.IndexOptions;
import core.framework.mongo.Mongo;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SchemaMigrationVProjectAttributionScopeTest {
    private static Document emptyCursor() {
        return new Document("cursor", new Document("firstBatch", new ArrayList<>())).append("n", 0);
    }

    private static boolean isGroupPipeline(Document command) {
        for (var stage : command.getList("pipeline", Document.class)) {
            if (stage.containsKey("$group")) return true;
        }
        return false;
    }

    @Test
    void createsUniquePartialFileIndexAndLookupIndexes() {
        var mongo = mock(Mongo.class);
        when(mongo.runCommand(any(Document.class))).thenReturn(emptyCursor());

        new SchemaMigrationVProjectAttributionScope().migrate(mongo);

        var indexes = ArgumentCaptor.forClass(Bson.class);
        var options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(mongo, times(1)).createIndex(eq(SchemaMigrationVProjectAttributionScope.COLLECTION), indexes.capture(), options.capture());
        verify(mongo, times(2)).createIndex(eq(SchemaMigrationVProjectAttributionScope.COLLECTION), any(Bson.class));

        var registry = MongoClientSettings.getDefaultCodecRegistry();
        var unique = indexes.getValue().toBsonDocument(BsonDocument.class, registry);
        assertEquals(List.of("project_id", "target_id"), List.copyOf(unique.keySet()));
        assertTrue(options.getValue().isUnique());
        var partial = options.getValue().getPartialFilterExpression();
        assertNotNull(partial, "uniqueness must only apply to file rows");
        assertEquals("file", partial.toBsonDocument(BsonDocument.class, registry).getString("target_type").getValue());
    }

    @Test
    void dedupeKeepsEarliestFileHomeAndDeletesTheRest() {
        var mongo = mock(Mongo.class);
        var commands = ArgumentCaptor.forClass(Document.class);
        when(mongo.runCommand(any(Document.class))).thenAnswer(invocation -> {
            Document command = invocation.getArgument(0);
            if (command.containsKey("aggregate") && isGroupPipeline(command)) {
                var group = new Document("_id", new Document("project_id", "p-1").append("target_id", "f-1"))
                    .append("count", 2)
                    .append("rows", List.of(
                        new Document("id", "later").append("created_at", new Date(2000)),
                        new Document("id", "earliest").append("created_at", new Date(1000))));
                return new Document("cursor", new Document("firstBatch", List.of(group)));
            }
            return emptyCursor();
        });

        new SchemaMigrationVProjectAttributionScope().migrate(mongo);

        verify(mongo, atLeastOnce()).runCommand(commands.capture());
        var deletes = commands.getAllValues().stream()
            .filter(c -> SchemaMigrationVProjectAttributionScope.COLLECTION.equals(c.getString("delete")))
            .toList();
        var idDelete = deletes.stream()
            .map(c -> (Document) c.getList("deletes", Document.class).get(0).get("q"))
            .filter(q -> q.containsKey("_id"))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var ids = (List<Object>) ((Document) idDelete.get("_id")).get("$in");
        assertEquals(List.of("later"), ids);
    }

    @Test
    void refreshesAttributorPrompt() {
        var mongo = mock(Mongo.class);
        var commands = ArgumentCaptor.forClass(Document.class);
        when(mongo.runCommand(any(Document.class))).thenReturn(emptyCursor());

        new SchemaMigrationVProjectAttributionScope().migrate(mongo);

        verify(mongo, atLeastOnce()).runCommand(commands.capture());
        var agentUpdate = commands.getAllValues().stream().filter(c -> "agents".equals(c.getString("update"))).findFirst().orElseThrow();
        var set = (Document) ((Document) agentUpdate.getList("updates", Document.class).get(0).get("u")).get("$set");
        assertTrue(set.getString("system_prompt").contains("exactly ONE subject"));
        assertEquals(set.getString("system_prompt"), set.getString("published_config.system_prompt"));
    }
}
