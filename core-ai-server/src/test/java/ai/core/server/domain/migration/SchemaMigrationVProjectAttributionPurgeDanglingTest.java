package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SchemaMigrationVProjectAttributionPurgeDanglingTest {
    @Test
    void deletesOrphansAcrossCursorBatches() {
        var mongo = mock(Mongo.class);
        when(mongo.runCommand(any(Document.class))).thenAnswer(invocation -> {
            Document command = invocation.getArgument(0);
            if (command.containsKey("aggregate")) {
                return new Document("cursor", new Document("id", 42L).append("firstBatch", List.of(new Document("_id", "a-1"), new Document("_id", "a-2"))));
            }
            if (command.containsKey("getMore")) {
                assertEquals(42L, command.getLong("getMore"));
                return new Document("cursor", new Document("id", 0L).append("nextBatch", List.of(new Document("_id", "a-3"))));
            }
            if (command.containsKey("delete")) {
                var q = (Document) command.getList("deletes", Document.class).getFirst().get("q");
                @SuppressWarnings("unchecked")
                var ids = (List<Object>) ((Document) q.get("_id")).get("$in");
                return new Document("n", ids.size());
            }
            return new Document();
        });

        new SchemaMigrationVProjectAttributionPurgeDangling().migrate(mongo);

        var commands = ArgumentCaptor.forClass(Document.class);
        verify(mongo, atLeastOnce()).runCommand(commands.capture());
        var aggregate = commands.getAllValues().stream().filter(c -> c.containsKey("aggregate")).findFirst().orElseThrow();
        var pipeline = aggregate.getList("pipeline", Document.class);
        assertEquals("file", ((Document) pipeline.getFirst().get("$match")).getString("target_type"));
        assertEquals("file_records", ((Document) pipeline.get(1).get("$lookup")).getString("from"));
        var deletes = commands.getAllValues().stream().filter(c -> c.containsKey("delete")).toList();
        assertEquals(2, deletes.size(), "one delete per cursor batch");
    }
}
