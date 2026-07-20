package ai.core.server.domain.migration;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.IndexOptions;
import core.framework.mongo.Mongo;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SchemaMigrationVSharedArtifactIndexesTest {
    @Test
    void createsSharedArtifactListIndexes() {
        var migration = new SchemaMigrationVSharedArtifactIndexes();
        var mongo = mock(Mongo.class);

        migration.migrate(mongo);

        var indexes = ArgumentCaptor.forClass(Bson.class);
        var options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(mongo, times(2)).createIndex(eq("file_records"), indexes.capture(), options.capture());

        var registry = MongoClientSettings.getDefaultCodecRegistry();
        var sharedAt = indexes.getAllValues().get(0).toBsonDocument(BsonDocument.class, registry);
        assertEquals(new BsonInt32(-1), sharedAt.get("shared_at"));

        var userSharedAt = indexes.getAllValues().get(1).toBsonDocument(BsonDocument.class, registry);
        assertEquals(List.of("user_id", "shared_at"), List.copyOf(userSharedAt.keySet()));
        assertEquals(new BsonInt32(1), userSharedAt.get("user_id"));
        assertEquals(new BsonInt32(-1), userSharedAt.get("shared_at"));

        for (var option : options.getAllValues()) {
            var partialFilter = option.getPartialFilterExpression();
            assertNotNull(partialFilter);
            assertTrue(partialFilter.toBsonDocument(BsonDocument.class, registry).containsKey("share_token"));
        }
    }
}
