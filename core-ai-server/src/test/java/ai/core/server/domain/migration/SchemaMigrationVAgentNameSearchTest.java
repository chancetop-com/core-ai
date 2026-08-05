package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SchemaMigrationVAgentNameSearchTest {
    @Test
    void backfillsNameKeyAndCreatesBothPickerIndexes() {
        var mongo = mock(Mongo.class);

        new SchemaMigrationVAgentNameSearch().migrate(mongo);

        verify(mongo).runCommand(argThat(command -> {
            var document = (Document) command;
            assertEquals("agents", document.getString("update"));
            var update = document.getList("updates", Document.class).getFirst();
            assertTrue(update.getBoolean("multi"));
            var pipeline = update.getList("u", Document.class);
            assertEquals("$toLower", pipeline.getFirst().get("$set", Document.class).get("name_key", Document.class).keySet().iterator().next());
            return true;
        }));
        verify(mongo, times(2)).createIndex(eq("agents"), any(Bson.class));
    }
}
