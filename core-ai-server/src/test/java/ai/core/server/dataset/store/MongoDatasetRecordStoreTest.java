package ai.core.server.dataset.store;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteError;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * The pre-PoC Mongo path, kept under a mocked core-ng collection to pin the existing query and session
 * behavior. It is the baseline of the three-store comparison.
 *
 * @author stephen
 */
class MongoDatasetRecordStoreTest {
    @SuppressWarnings("unchecked")
    private final MongoCollection<DatasetRecord> collection = mock(MongoCollection.class);
    private final MongoDatasetRecordStore store = store();

    private MongoDatasetRecordStore store() {
        var s = new MongoDatasetRecordStore();
        s.datasetRecordCollection = collection;
        return s;
    }

    private DatasetRecord datasetRecord(String id, String data) {
        var record = new DatasetRecord();
        record.id = id;
        record.datasetId = "ds1";
        record.data = data;
        return record;
    }

    @Test
    void queryWithoutDataFilterDelegatesPaginationToMongo() {
        when(collection.find(any(Query.class))).thenReturn(List.of());
        when(collection.count(any(Bson.class))).thenReturn(0L);

        store.query(new DatasetRecordService.QueryRequest("ds1", null, null, null, 10, 5, null, null));

        var captor = ArgumentCaptor.forClass(Query.class);
        verify(collection).find(captor.capture());
        assertEquals(10, captor.getValue().limit);
        assertEquals(5, captor.getValue().skip);
        assertNull(captor.getValue().projection);
    }

    @Test
    void queryWithDataFilterMatchesInMemoryAndPaginatesAfterFilter() {
        when(collection.find(any(Query.class))).thenReturn(List.of(
                datasetRecord("r1", "{\"status\":\"done\",\"priority\":3}"),
                datasetRecord("r2", "{\"status\":\"pending\",\"priority\":3}"),
                datasetRecord("r3", "{\"status\":\"done\",\"priority\":1}")));

        var result = store.query(new DatasetRecordService.QueryRequest("ds1", null, null, null, 1, 1, null,
                Map.of("status", "done")));

        assertEquals(2L, result.total());
        assertEquals(1, result.records().size());
        assertEquals("r3", result.records().get(0).id);
        verify(collection, never()).count(any(Bson.class));
    }

    @Test
    void queryWithDataFilterMatchesNestedDotPath() {
        when(collection.find(any(Query.class))).thenReturn(List.of(
                datasetRecord("r1", "{\"meta\":{\"priority\":3}}"),
                datasetRecord("r2", "{\"meta\":{\"priority\":1}}")));

        var result = store.query(new DatasetRecordService.QueryRequest("ds1", null, null, null, null, null, null,
                Map.of("meta.priority", 3)));

        assertEquals(1L, result.total());
        assertEquals("r1", result.records().get(0).id);
    }

    @Test
    void queryWithDataFilterMatchesNumbersAcrossTypes() {
        when(collection.find(any(Query.class))).thenReturn(List.of(
                datasetRecord("r1", "{\"priority\":3}"),
                datasetRecord("r2", "{\"priority\":3.0}"),
                datasetRecord("r3", "{\"priority\":4}")));

        var result = store.query(new DatasetRecordService.QueryRequest("ds1", null, null, null, null, null, null,
                Map.of("priority", 3.0)));

        assertEquals(2L, result.total());
    }

    @Test
    void queryClampsLimitToMaxPageSize() {
        when(collection.find(any(Query.class))).thenReturn(List.of());
        when(collection.count(any(Bson.class))).thenReturn(0L);

        store.query(new DatasetRecordService.QueryRequest("ds1", null, null, null, 5000, null, null, null));

        var captor = ArgumentCaptor.forClass(Query.class);
        verify(collection).find(captor.capture());
        assertEquals(DatasetRecordService.MAX_PAGE_SIZE, captor.getValue().limit);
    }

    @Test
    void queryWithDataFilterCapsScanAndReportsTruncation() {
        var scanned = IntStream.range(0, DatasetRecordService.MAX_FILTER_SCAN_RECORDS)
                .mapToObj(i -> datasetRecord("r" + i, "{\"status\":\"done\"}"))
                .toList();
        when(collection.find(any(Query.class))).thenReturn(scanned);

        var result = store.query(new DatasetRecordService.QueryRequest("ds1", null, null, null, 10, null, null,
                Map.of("status", "done")));

        assertTrue(result.truncated());
        assertEquals(10, result.records().size());
        var captor = ArgumentCaptor.forClass(Query.class);
        verify(collection).find(captor.capture());
        assertEquals(DatasetRecordService.MAX_FILTER_SCAN_RECORDS, captor.getValue().limit);
        assertNull(captor.getValue().skip);
    }

    @Test
    void queryWithDataFilterNotTruncatedWhenScanUnderCap() {
        when(collection.find(any(Query.class))).thenReturn(List.of(
                datasetRecord("r1", "{\"status\":\"done\"}")));

        var result = store.query(new DatasetRecordService.QueryRequest("ds1", null, null, null, null, null, null,
                Map.of("status", "done")));

        assertFalse(result.truncated());
    }

    @Test
    void queryWithDataFilterSkipsRecordsWithInvalidData() {
        when(collection.find(any(Query.class))).thenReturn(List.of(
                datasetRecord("r1", "not-json"),
                datasetRecord("r2", "[1,2]"),
                datasetRecord("r3", "{\"status\":\"done\"}")));

        var result = store.query(new DatasetRecordService.QueryRequest("ds1", null, null, null, null, null, null,
                Map.of("status", "done")));

        assertEquals(1L, result.total());
        assertEquals("r3", result.records().get(0).id);
    }

    @Test
    void queryWithFieldsKeepsInvalidDataUntouched() {
        when(collection.find(any(Query.class))).thenReturn(List.of(
                datasetRecord("r1", "not-json")));
        when(collection.count(any(Bson.class))).thenReturn(1L);

        var result = store.query(new DatasetRecordService.QueryRequest("ds1", null, null, List.of("status"), null, null, null, null));

        assertEquals("not-json", result.records().get(0).data);
    }

    @Test
    void queryWithFieldsTrimsDataKeys() {
        when(collection.find(any(Query.class))).thenReturn(List.of(
                datasetRecord("r1", "{\"status\":\"done\",\"priority\":3}")));

        var result = store.query(new DatasetRecordService.QueryRequest("ds1", null, null, List.of("status"), null, null, null, null));

        var data = JsonUtil.toMap(result.records().get(0).data);
        assertEquals(1, data.size());
        assertEquals("done", data.get("status"));
    }

    @Test
    void upsertBySessionCreatesNewRecordWhenAbsent() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        store.upsertBySession("ds1", "s1", "{\"menu\":1}", "agent1", "u1");

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

        store.upsertBySession("ds1", "s1", "{\"new\":true}", "agent1", "u1");

        verify(collection, never()).insert(any());
        assertEquals("{\"new\":true}", existing.data);
        assertEquals("u1", existing.updatedBy);
        verify(collection).replace(existing);
    }

    @Test
    void upsertBySessionFallsBackToSystemUserWhenUserNull() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        store.upsertBySession("ds1", "s1", "{}", "agent1", null);

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

        store.upsertBySession("ds1", "s1", "{\"data\":1}", "agent1", "u1");

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
                () -> store.upsertBySession("ds1", "s1", "{}", "agent1", "u1"));
    }

    @Test
    void findBySessionUsesDatasetAndSessionFilter() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        var result = store.findBySession("ds1", "s1");

        assertEquals(Optional.empty(), result);
        var captor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).findOne(captor.capture());
        var doc = captor.getValue().toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        assertTrue(doc.containsKey("$and"));
        var filters = doc.getArray("$and");
        assertTrue(filters.stream().anyMatch(value -> value.asDocument().containsKey("dataset_id")));
        assertTrue(filters.stream().anyMatch(value -> value.asDocument().containsKey("session_id")));
    }

    @Test
    void updateMergesSubsetFieldsIntoExistingData() {
        var existing = new DatasetRecord();
        existing.id = "r1";
        existing.datasetId = "ds1";
        existing.data = "{\"name\":\"卢锦锦\",\"status\":\"已评估\",\"evaluation_result\":\"推荐面试\"}";
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.of(existing));

        var updated = store.update("ds1", "r1", Map.of("status", "已面试"), "u1");

        assertTrue(updated);
        var merged = JsonUtil.toMap(existing.data);
        assertEquals("卢锦锦", merged.get("name"));
        assertEquals("推荐面试", merged.get("evaluation_result"));
        assertEquals("已面试", merged.get("status"));
        assertEquals("u1", existing.updatedBy);
        verify(collection).replace(existing);
    }

    @Test
    void updateOverwritesProvidedFieldsAndPreservesOthers() {
        var existing = new DatasetRecord();
        existing.id = "r1";
        existing.datasetId = "ds1";
        existing.data = "{\"name\":\"old\",\"status\":\"已评估\"}";
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.of(existing));

        var updated = store.update("ds1", "r1", Map.of("name", "new"), "u1");

        assertTrue(updated);
        var merged = JsonUtil.toMap(existing.data);
        assertEquals("new", merged.get("name"));
        assertEquals("已评估", merged.get("status"));
    }

    @Test
    void updateReturnsFalseWhenRecordAbsent() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        var updated = store.update("ds1", "missing", Map.of("status", "x"), "u1");

        assertFalse(updated);
        verify(collection, never()).replace(any());
    }

    @Test
    void updateAndDeleteOnlyTouchRecordsOfTheGivenDataset() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        assertFalse(store.update("ds-other", "r1", Map.of("status", "x"), "u1"));
        assertFalse(store.delete("ds-other", "r1"));

        var captor = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).findOne(captor.capture());
        for (var filter : captor.getAllValues()) {
            var filters = filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry())
                    .getArray("$and");
            assertTrue(filters.stream().anyMatch(value -> value.asDocument().containsKey("_id")));
            assertTrue(filters.stream().anyMatch(value -> value.asDocument().containsKey("dataset_id")));
        }
        verify(collection, never()).replace(any());
        verify(collection, never()).delete(any(String.class));
    }

    @Test
    void updateWithNullDeletesField() {
        var existing = new DatasetRecord();
        existing.id = "r1";
        existing.datasetId = "ds1";
        existing.data = "{\"name\":\"卢锦锦\",\"status\":\"已评估\"}";
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.of(existing));
        var patch = new java.util.HashMap<String, Object>();
        patch.put("status", null);

        var updated = store.update("ds1", "r1", patch, "u1");

        assertTrue(updated);
        var merged = JsonUtil.toMap(existing.data);
        assertEquals(1, merged.size());
        assertEquals("卢锦锦", merged.get("name"));
    }

    @Test
    void patchBySessionCreatesRecordWhenAbsent() {
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());

        store.patchBySession("ds1", "s1", "{\"business_type\":\"QSR\"}", "agent1", "u1");

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

        store.patchBySession("ds1", "s1", "{\"menu_source\":\"OFFLINE\"}", "agent1", "u1");

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
                () -> store.upsertBySession("ds1", "s1", big, "agent1", "u1"));
        verify(collection, never()).insert(any());
    }
}
