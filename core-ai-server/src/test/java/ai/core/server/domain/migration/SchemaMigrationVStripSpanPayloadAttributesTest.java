package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import org.bson.Document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaMigrationVStripSpanPayloadAttributesTest {
    @Test
    void pagesByIdAndRebuildsAttributesWithoutPayloadKeys() {
        var mongo = mock(Mongo.class);
        var page = new Document("cursor", new Document("firstBatch", List.of(
            new Document("_id", "id-1"),
            new Document("_id", "id-2"))));
        var emptyPage = new Document("cursor", new Document("firstBatch", List.of()));
        when(mongo.runCommand(any())).thenReturn(page, new Document("n", 2), emptyPage);

        new SchemaMigrationVStripSpanPayloadAttributes().migrate(mongo);

        verify(mongo, times(2)).runCommand(argThat(command -> {
            var find = (Document) command;
            if (!"spans".equals(find.getString("find"))) return false;
            var filter = find.get("filter", Document.class);
            if (!filter.get("_id", Document.class).containsKey("$gt")) return false;
            return Integer.valueOf(1).equals(find.get("sort", Document.class).getInteger("_id"))
                && Integer.valueOf(1).equals(find.get("projection", Document.class).getInteger("_id"));
        }));
        verify(mongo).runCommand(argThat(command -> {
            var update = (Document) command;
            if (!"spans".equals(update.getString("update"))) return false;
            var updateSpec = update.getList("updates", Document.class).getFirst();
            if (!Boolean.TRUE.equals(updateSpec.getBoolean("multi"))) return false;
            var pipeline = updateSpec.getList("u", Document.class);
            var set = pipeline.getFirst().get("$set", Document.class);
            var arrayToObject = set.get("attributes", Document.class).get("$arrayToObject", Document.class);
            var filter = arrayToObject.get("$filter", Document.class);
            var cond = filter.get("cond", Document.class);
            var inList = (List<?>) cond.get("$not", Document.class).get("$in");
            if (inList.size() < 2 || !(inList.get(1) instanceof List<?> excluded)) return false;
            return excluded.equals(List.of("langfuse.observation.input", "langfuse.observation.output",
                "gen_ai.prompt", "gen_ai.completion"));
        }));
    }
}
