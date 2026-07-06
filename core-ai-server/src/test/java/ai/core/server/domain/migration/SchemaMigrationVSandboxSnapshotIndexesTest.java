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

class SchemaMigrationVSandboxSnapshotIndexesTest {
    @Test
    void createsResumeLookupAndExpiryIndexes() {
        var migration = new SchemaMigrationVSandboxSnapshotIndexes();
        var mongo = mock(Mongo.class);

        migration.migrate(mongo);

        var index = ArgumentCaptor.forClass(Bson.class);
        verify(mongo, times(2)).createIndex(eq("sandbox_snapshots"), index.capture());
        var registry = MongoClientSettings.getDefaultCodecRegistry();

        var lookup = index.getAllValues().get(0).toBsonDocument(BsonDocument.class, registry);
        // BsonDocument preserves insertion order, so this pins the compound index key order
        assertEquals(List.of("session_id", "status", "created_at"), List.copyOf(lookup.keySet()));
        assertEquals(new BsonInt32(1), lookup.get("session_id"));
        assertEquals(new BsonInt32(1), lookup.get("status"));
        assertEquals(new BsonInt32(-1), lookup.get("created_at"));

        var expiry = index.getAllValues().get(1).toBsonDocument(BsonDocument.class, registry);
        assertEquals(new BsonInt32(1), expiry.get("expires_at"));
    }
}
