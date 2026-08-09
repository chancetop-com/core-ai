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

class SchemaMigrationVArtifactListIndexesTest {
    @Test
    void createsArtifactListIndexes() {
        var migration = new SchemaMigrationVArtifactListIndexes();
        var mongo = mock(Mongo.class);

        migration.migrate(mongo);

        var indexes = ArgumentCaptor.forClass(Bson.class);
        verify(mongo, times(1)).createIndex(eq("file_records"), indexes.capture());
        verify(mongo, times(1)).createIndex(eq("chat_sessions"), indexes.capture());

        var registry = MongoClientSettings.getDefaultCodecRegistry();
        var userCreatedAt = indexes.getAllValues().get(0).toBsonDocument(BsonDocument.class, registry);
        assertEquals(List.of("user_id", "created_at"), List.copyOf(userCreatedAt.keySet()));
        assertEquals(new BsonInt32(1), userCreatedAt.get("user_id"));
        assertEquals(new BsonInt32(-1), userCreatedAt.get("created_at"));

        var sessionIndex = indexes.getAllValues().get(1).toBsonDocument(BsonDocument.class, registry);
        assertEquals(List.of("user_id", "artifacts.file_id"), List.copyOf(sessionIndex.keySet()));
        assertEquals(new BsonInt32(1), sessionIndex.get("user_id"));
        assertEquals(new BsonInt32(1), sessionIndex.get("artifacts.file_id"));
    }
}
