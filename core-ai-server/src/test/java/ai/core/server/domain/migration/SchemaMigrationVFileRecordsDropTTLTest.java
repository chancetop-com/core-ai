package ai.core.server.domain.migration;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.IndexOptions;
import core.framework.mongo.Mongo;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SchemaMigrationVFileRecordsDropTTLTest {
    @Test
    void replacesBlanketTTLWithInlineDataOnlyTTL() {
        var migration = new SchemaMigrationVFileRecordsDropTTL();
        var mongo = mock(Mongo.class);

        migration.migrate(mongo);

        var registry = MongoClientSettings.getDefaultCodecRegistry();
        var dropped = ArgumentCaptor.forClass(Bson.class);
        verify(mongo).dropIndex(eq("file_records"), dropped.capture());
        assertEquals(new BsonInt32(1), dropped.getValue().toBsonDocument(BsonDocument.class, registry).get("created_at"));

        var index = ArgumentCaptor.forClass(Bson.class);
        var options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(mongo).createIndex(eq("file_records"), index.capture(), options.capture());
        assertEquals(new BsonInt32(1), index.getValue().toBsonDocument(BsonDocument.class, registry).get("created_at"));

        var option = options.getValue();
        assertEquals(SchemaMigrationVFileRecordsDropTTL.INLINE_DATA_TTL_SECONDS, option.getExpireAfter(TimeUnit.SECONDS));
        var partialFilter = option.getPartialFilterExpression();
        assertNotNull(partialFilter, "TTL must be partial so object-storage records never expire");
        var filter = partialFilter.toBsonDocument(BsonDocument.class, registry);
        assertEquals("string", filter.getDocument("data").getString("$type").getValue());
    }

    @Test
    void toleratesMissingLegacyIndex() {
        var migration = new SchemaMigrationVFileRecordsDropTTL();
        var mongo = mock(Mongo.class);
        doThrow(new RuntimeException("index not found")).when(mongo).dropIndex(eq("file_records"), any(Bson.class));

        migration.migrate(mongo);

        verify(mongo).createIndex(eq("file_records"), any(Bson.class), any(IndexOptions.class));
    }
}
