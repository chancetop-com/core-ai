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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SchemaMigrationVSeoOpsIndexesTest {
    @Test
    void createsUniqueIdentityAndOperationalQueryIndexes() {
        var migration = new SchemaMigrationVSeoOpsIndexes();
        var mongo = mock(Mongo.class);

        migration.migrate(mongo);

        assertEquals("20260817001", migration.version());
        verify(mongo, times(3)).createIndex(eq("seo_merchants"), any(Bson.class), any(IndexOptions.class));
        verify(mongo, times(3)).createIndex(eq("seo_locations"), any(Bson.class), any(IndexOptions.class));
        verify(mongo, times(4)).createIndex(eq("seo_tasks"), any(Bson.class), any(IndexOptions.class));

        var index = ArgumentCaptor.forClass(Bson.class);
        var options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(mongo, times(4)).createIndex(eq("seo_tasks"), index.capture(), options.capture());
        var registry = MongoClientSettings.getDefaultCodecRegistry();
        var taskIndexes = index.getAllValues().stream()
            .map(value -> value.toBsonDocument(BsonDocument.class, registry))
            .toList();

        assertEquals(List.of("merchant_id", "creation_idempotency_key"), List.copyOf(taskIndexes.get(0).keySet()));
        assertTrue(options.getAllValues().get(0).isUnique());
        assertEquals(List.of("merchant_id", "priority_rank", "due_at", "updated_at"),
            List.copyOf(taskIndexes.get(1).keySet()));
        assertEquals(List.of("owner_id", "priority_rank", "due_at", "updated_at"),
            List.copyOf(taskIndexes.get(2).keySet()));
        assertEquals(new BsonInt32(-1), taskIndexes.get(3).get("updated_at"));
        assertFalse(options.getAllValues().get(3).isUnique());
    }
}
