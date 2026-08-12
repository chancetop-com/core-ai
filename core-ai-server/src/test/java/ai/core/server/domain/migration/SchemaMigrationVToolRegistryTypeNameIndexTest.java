package ai.core.server.domain.migration;

import com.mongodb.MongoClientSettings;
import core.framework.mongo.Mongo;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SchemaMigrationVToolRegistryTypeNameIndexTest {
    @Test
    void usesFreshVersionAndCreatesTypeNameIndex() {
        var migration = new SchemaMigrationVToolRegistryTypeNameIndex();
        var mongo = mock(Mongo.class);

        assertEquals("20260812001", migration.version());
        migration.migrate(mongo);

        var index = ArgumentCaptor.forClass(Bson.class);
        verify(mongo).createIndex(eq("tool_registry"), index.capture());
        var document = index.getValue()
            .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertEquals(new BsonInt32(1), document.get("type"));
        assertEquals(new BsonInt32(1), document.get("name"));
    }
}
