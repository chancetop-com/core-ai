package ai.core.server.domain.migration;

import com.mongodb.MongoClientSettings;
import core.framework.mongo.Mongo;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @author Stephen
 */
class SchemaMigrationVMediaJobCostIndexesTest {
    @Test
    void usesFreshVersionAndCreatesCostListIndexes() {
        var migration = new SchemaMigrationVMediaJobCostIndexes();
        var mongo = mock(Mongo.class);

        assertEquals("20260829001", migration.version());
        migration.migrate(mongo);

        var index = ArgumentCaptor.forClass(Bson.class);
        verify(mongo, times(2)).createIndex(eq("media_jobs"), index.capture());
        var created = index.getAllValues().stream()
            .map(keys -> keys.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry()))
            .toList();
        assertEquals(List.of(
            new BsonDocument("created_at", new BsonInt32(-1)),
            new BsonDocument("media_type", new BsonInt32(1)).append("created_at", new BsonInt32(-1))), created);
    }
}
