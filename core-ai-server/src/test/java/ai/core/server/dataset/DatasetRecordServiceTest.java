package ai.core.server.dataset;

import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteError;
import core.framework.mongo.MongoCollection;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetRecordServiceTest {
    @SuppressWarnings("unchecked")
    private final MongoCollection<DatasetRecord> collection = mock(MongoCollection.class);
    private final DatasetRecordService service = service();

    private DatasetRecordService service() {
        var s = new DatasetRecordService();
        s.datasetRecordCollection = collection;
        return s;
    }

    @Test
    void upsertBySessionCreatesNewRecordWhenAbsent() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        service.upsertBySession("ds1", "s1", "{\"menu\":1}", "agent1", "u1");

        var captor = ArgumentCaptor.forClass(DatasetRecord.class);
        verify(collection).insert(captor.capture());
        var record = captor.getValue();
        assertEquals("ds1", record.datasetId);
        assertEquals("s1", record.sessionId);
        assertEquals("agent1", record.agentId);
        assertNull(record.runId);
        assertNotNull(record.createdAt);
        assertNotNull(record.updatedAt);
        assertEquals("u1", record.createdBy);
    }

    @Test
    void upsertBySessionReplacesExistingRecord() {
        var existing = new DatasetRecord();
        existing.id = "r1";
        existing.datasetId = "ds1";
        existing.sessionId = "s1";
        existing.data = "{\"old\":true}";
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.of(existing));

        service.upsertBySession("ds1", "s1", "{\"new\":true}", "agent1", "u1");

        verify(collection, never()).insert(any());
        assertEquals("{\"new\":true}", existing.data);
        assertEquals("u1", existing.updatedBy);
        verify(collection).replace(existing);
    }

    @Test
    void upsertBySessionFallsBackToSystemUserWhenUserNull() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        service.upsertBySession("ds1", "s1", "{}", "agent1", null);

        var captor = ArgumentCaptor.forClass(DatasetRecord.class);
        verify(collection).insert(captor.capture());
        assertEquals("system", captor.getValue().createdBy);
        assertEquals("system", captor.getValue().updatedBy);
    }

    @Test
    void upsertBySessionRetriesAsUpdateOnDuplicateKey() {
        var winner = new DatasetRecord();
        winner.id = "r2";
        winner.datasetId = "ds1";
        winner.sessionId = "s1";
        when(collection.findOne(any(Bson.class)))
                .thenReturn(Optional.empty())   // first read: absent
                .thenReturn(Optional.of(winner)); // retry read after duplicate key: winner exists
        var duplicateKey = mock(MongoWriteException.class);
        when(duplicateKey.getError()).thenReturn(new WriteError(11000, "E11000 duplicate key", new BsonDocument()));
        doThrow(duplicateKey).when(collection).insert(any());

        service.upsertBySession("ds1", "s1", "{\"data\":1}", "agent1", "u1");

        verify(collection, times(1)).insert(any());
        verify(collection).replace(winner);
        assertEquals("{\"data\":1}", winner.data);
    }

    @Test
    void upsertBySessionRejectsNonDuplicateWriteErrors() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());
        var otherError = mock(MongoWriteException.class);
        when(otherError.getError()).thenReturn(new WriteError(2, "bad value", new BsonDocument()));
        doThrow(otherError).when(collection).insert(any());

        assertThrows(MongoWriteException.class,
                () -> service.upsertBySession("ds1", "s1", "{}", "agent1", "u1"));
    }

    @Test
    void queryBySessionUsesDatasetAndSessionFilter() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        var result = service.queryBySession("ds1", "s1");

        assertEquals(Optional.empty(), result);
        var captor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).findOne(captor.capture());
        var doc = captor.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertTrue(doc.containsKey("$and"));
        var rendered = doc.getArray("$and").toString();
        assertTrue(rendered.contains("dataset_id"));
        assertTrue(rendered.contains("session_id"));
    }

    @Test
    void mergeRecursivelyMergesObjects() {
        var current = Map.<String, Object>of(
                "business_type", "QSR",
                "raw_menu", Map.of("restaurantInfo", Map.of("name", "Old"), "categories", "keep"));
        var patch = Map.<String, Object>of(
                "raw_menu", Map.of("restaurantInfo", Map.of("name", "New")));

        var merged = DatasetRecordService.merge(current, patch);

        assertEquals("QSR", merged.get("business_type"));
        @SuppressWarnings("unchecked")
        var rawMenu = (Map<String, Object>) merged.get("raw_menu");
        assertEquals("keep", rawMenu.get("categories"));
        @SuppressWarnings("unchecked")
        var restaurantInfo = (Map<String, Object>) rawMenu.get("restaurantInfo");
        assertEquals("New", restaurantInfo.get("name"));
    }

    @Test
    void mergeReplacesArraysAndScalars() {
        var current = Map.<String, Object>of(
                "categories", "old-array",
                "menu_source", "OFFLINE");
        var patch = Map.<String, Object>of(
                "categories", "new-array",
                "menu_source", "ONLINE");

        var merged = DatasetRecordService.merge(current, patch);

        assertEquals("new-array", merged.get("categories"));
        assertEquals("ONLINE", merged.get("menu_source"));
    }

    @Test
    void mergeNullDeletesField() {
        var current = Map.<String, Object>of("menu_source", "ONLINE", "keep", true);
        var patch = new java.util.HashMap<String, Object>();
        patch.put("menu_source", null);

        var merged = DatasetRecordService.merge(current, patch);

        assertEquals(1, merged.size());
        assertEquals(Boolean.TRUE, merged.get("keep"));
    }

    @Test
    void patchBySessionCreatesRecordWhenAbsent() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        service.patchBySession("ds1", "s1", "{\"business_type\":\"QSR\"}", "agent1", "u1");

        var captor = ArgumentCaptor.forClass(DatasetRecord.class);
        verify(collection).insert(captor.capture());
        assertEquals("{\"business_type\":\"QSR\"}", captor.getValue().data);
        assertEquals("s1", captor.getValue().sessionId);
    }

    @Test
    void patchBySessionMergesIntoExistingState() {
        var existing = new DatasetRecord();
        existing.id = "r1";
        existing.datasetId = "ds1";
        existing.sessionId = "s1";
        existing.data = "{\"business_type\":\"QSR\",\"menu_source\":\"ONLINE\"}";
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.of(existing));

        service.patchBySession("ds1", "s1", "{\"menu_source\":\"OFFLINE\"}", "agent1", "u1");

        var merged = JsonUtil.toMap(existing.data);
        assertEquals("QSR", merged.get("business_type"));
        assertEquals("OFFLINE", merged.get("menu_source"));
        verify(collection).replace(existing);
    }

    @Test
    void upsertBySessionRejectsOversizedState() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());
        var big = "{\"blob\":\"" + "x".repeat(DatasetRecordService.MAX_STATE_BYTES) + "\"}";

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertBySession("ds1", "s1", big, "agent1", "u1"));
        verify(collection, never()).insert(any());
    }
}
