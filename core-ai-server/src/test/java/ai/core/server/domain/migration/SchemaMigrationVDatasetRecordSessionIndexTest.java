package ai.core.server.domain.migration;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.IndexOptions;
import core.framework.mongo.Mongo;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SchemaMigrationVDatasetRecordSessionIndexTest {
    @Test
    void createsPartialUniqueCompoundIndex() {
        var migration = new SchemaMigrationVDatasetRecordSessionIndex();
        var mongo = mock(Mongo.class);

        migration.migrate(mongo);

        var indexes = ArgumentCaptor.forClass(Bson.class);
        var options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(mongo).createIndex(eq("dataset_records"), indexes.capture(), options.capture());

        var registry = MongoClientSettings.getDefaultCodecRegistry();
        var keys = indexes.getValue().toBsonDocument(BsonDocument.class, registry);
        assertEquals(new BsonInt32(1), keys.get("dataset_id"));
        assertEquals(new BsonInt32(1), keys.get("session_id"));

        var option = options.getValue();
        assertTrue(option.isUnique());
        var partialFilter = option.getPartialFilterExpression();
        assertNotNull(partialFilter);
        assertTrue(partialFilter.toBsonDocument(BsonDocument.class, registry).containsKey("session_id"));
    }
}
