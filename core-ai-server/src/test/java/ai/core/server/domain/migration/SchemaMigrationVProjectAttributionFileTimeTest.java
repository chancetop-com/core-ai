package ai.core.server.domain.migration;

import com.mongodb.MongoClientSettings;
import core.framework.mongo.Mongo;
import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SchemaMigrationVProjectAttributionFileTimeTest {
    @Test
    void backfillsFileTimeFromFileRecordsAndAddsSortIndexes() {
        var mongo = mock(Mongo.class);
        when(mongo.runCommand(any(Document.class))).thenReturn(new Document("cursor", new Document("firstBatch", List.of())));

        new SchemaMigrationVProjectAttributionFileTime().migrate(mongo);

        var command = ArgumentCaptor.forClass(Document.class);
        verify(mongo).runCommand(command.capture());
        var pipeline = command.getValue().getList("pipeline", Document.class);
        var match = (Document) pipeline.getFirst().get("$match");
        assertEquals("file", match.getString("target_type"));
        assertEquals(BsonNull.VALUE, match.get("target_created_at"), "null equality keeps the scan index-served");
        var lookup = (Document) pipeline.get(1).get("$lookup");
        assertEquals("file_records", lookup.getString("from"));
        var merge = (Document) pipeline.getLast().get("$merge");
        assertEquals(SchemaMigrationVProjectAttributionFileTime.COLLECTION, merge.getString("into"));
        assertEquals("discard", merge.getString("whenNotMatched"));

        var indexes = ArgumentCaptor.forClass(Bson.class);
        verify(mongo, times(2)).createIndex(eq(SchemaMigrationVProjectAttributionFileTime.COLLECTION), indexes.capture());
        var registry = MongoClientSettings.getDefaultCodecRegistry();
        var keys = indexes.getAllValues().stream().map(b -> b.toBsonDocument(BsonDocument.class, registry)).toList();
        assertEquals(List.of("project_id", "target_type", "target_created_at"), List.copyOf(keys.get(0).keySet()));
        assertEquals(List.of("subject_id", "target_type", "target_created_at"), List.copyOf(keys.get(1).keySet()));
        assertTrue(keys.stream().allMatch(k -> k.getInt32("target_created_at").getValue() == -1), "newest first");
    }
}
