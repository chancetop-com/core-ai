package ai.core.server.dataset.store;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour every dataset record store must agree on. Implementations decide how to run it: against a
 * mocked core-ng collection, a real Mongo, or a real PostgreSQL.
 *
 * @author stephen
 */
abstract class DatasetRecordStoreContractTest {
    protected static final ZonedDateTime T0 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private String datasetId;

    @BeforeEach
    void setUpDataset() {
        datasetId = "ds-" + UUID.randomUUID();
        prepareDataset(datasetId);
    }

    @AfterEach
    void tearDownDataset() {
        store().deleteByDataset(datasetId);
        cleanupDataset(datasetId);
    }

    protected abstract DatasetRecordStore store();

    // hook for stores that materialize per-dataset state (views); the default store has none
    protected void prepareDataset(String datasetId) {
    }

    protected void cleanupDataset(String datasetId) {
    }

    protected String datasetId() {
        return datasetId;
    }

    protected String rid(String id) {
        return datasetId + "-" + id;
    }

    protected DatasetRecord record(String id, String data, ZonedDateTime runStartedAt) {
        var record = new DatasetRecord();
        record.id = rid(id);
        record.datasetId = datasetId;
        record.agentId = "agent1";
        record.data = data;
        record.runStartedAt = runStartedAt;
        record.createdAt = runStartedAt;
        record.createdBy = "u1";
        record.updatedAt = runStartedAt;
        record.updatedBy = "u1";
        return record;
    }

    protected void insert(String id, String data, ZonedDateTime runStartedAt) {
        store().insert(record(id, data, runStartedAt));
    }

    protected DatasetRecordService.QueryResult query(Map<String, Object> filter) {
        return store().query(new DatasetRecordService.QueryRequest(datasetId, null, null, null, 100, 0, null, filter));
    }

    protected Map<String, Object> dataOf(DatasetRecord record) {
        return JsonUtil.toMap(record.data);
    }

    @Test
    void filterMatchesPointPathAndScalars() {
        insert("r1", "{\"status\":\"done\",\"meta\":{\"priority\":3}}", T0);
        insert("r2", "{\"status\":\"pending\",\"meta\":{\"priority\":4}}", T0.plusSeconds(1));

        assertEquals(1, query(Map.of("status", "done")).total());
        assertEquals(1, query(Map.of("meta.priority", 3)).total());
        assertEquals(1, query(Map.of("meta.priority", 4)).total());
        assertEquals(0, query(Map.of("meta.priority", 5)).total());
    }

    @Test
    void timestampsRoundTripAsInstants() {
        insert("r1", "{\"a\":1}", T0);

        var queried = store().query(new DatasetRecordService.QueryRequest(datasetId, null, null, null, 10, 0, null, null))
                .records().stream().filter(row -> rid("r1").equals(row.id)).findFirst().orElseThrow();
        assertEquals(T0.toInstant(), queried.runStartedAt.toInstant());
        assertEquals(T0.toInstant(), queried.createdAt.toInstant());
        assertEquals(T0.toInstant(), queried.updatedAt.toInstant());
    }

    @Test
    void numbersCompareByValueAcrossTypes() {
        insert("r1", "{\"priority\":3}", T0);
        insert("r2", "{\"priority\":3.0}", T0.plusSeconds(1));
        insert("r3", "{\"priority\":4}", T0.plusSeconds(2));

        assertEquals(2, query(Map.of("priority", 3.0)).total());
        assertEquals(2, query(Map.of("priority", 3)).total());
    }

    @Test
    void stringsDoNotEqualNumbers() {
        insert("r1", "{\"priority\":3}", T0);
        insert("r2", "{\"priority\":\"3\"}", T0.plusSeconds(1));

        var result = query(Map.of("priority", 3));
        assertEquals(1, result.total());
        assertEquals(rid("r1"), result.records().getFirst().id);
    }

    @Test
    void neNullMatchesOnlyNonNullValues() {
        insert("r1", "{\"status\":\"done\"}", T0);
        insert("r2", "{\"status\":null}", T0.plusSeconds(1));
        insert("r3", "{\"other\":1}", T0.plusSeconds(2));

        var ne = new HashMap<String, Object>();
        ne.put("$ne", null);
        assertEquals(1, query(Map.of("status", ne)).total());
    }

    @Test
    void nullMatchesMissingFieldAndExplicitNull() {
        insert("r1", "{\"other\":\"x\"}", T0);
        insert("r2", "{\"status\":null}", T0.plusSeconds(1));
        insert("r3", "{\"status\":\"done\"}", T0.plusSeconds(2));

        assertEquals(2, query(emptyFilterWithNull()).total());
    }

    private Map<String, Object> emptyFilterWithNull() {
        var filter = new LinkedHashMap<String, Object>();
        filter.put("status", null);
        return filter;
    }

    @Test
    void objectEqualityIgnoresKeyOrder() {
        insert("r1", "{\"meta\":{\"a\":1,\"b\":2}}", T0);
        insert("r2", "{\"meta\":{\"a\":1,\"b\":2,\"c\":3}}", T0.plusSeconds(1));

        var expected = new LinkedHashMap<String, Object>();
        expected.put("b", 2);
        expected.put("a", 1);
        assertEquals(1, query(Map.of("meta", expected)).total());
    }

    @Test
    void arrayEqualityIsExactNotContainment() {
        insert("r1", "{\"tags\":[\"a\",\"b\"]}", T0);
        insert("r2", "{\"tags\":[[\"a\",\"b\"],\"c\"]}", T0.plusSeconds(1));
        insert("r3", "{\"tags\":[\"a\"]}", T0.plusSeconds(2));

        assertEquals(1, query(Map.of("tags", List.of("a", "b"))).total());
    }

    @Test
    void comparisonOperators() {
        insert("r1", "{\"score\":10,\"status\":\"alpha\"}", T0);
        insert("r2", "{\"score\":20,\"status\":\"beta\"}", T0.plusSeconds(1));
        insert("r3", "{\"status\":\"gamma\"}", T0.plusSeconds(2));

        assertEquals(1, query(Map.of("score", Map.of("$gt", 10))).total());
        assertEquals(2, query(Map.of("score", Map.of("$gte", 10))).total());
        assertEquals(1, query(Map.of("score", Map.of("$lt", 20))).total());
        assertEquals(2, query(Map.of("status", Map.of("$in", List.of("alpha", "gamma")))).total());
        assertEquals(2, query(Map.of("score", Map.of("$exists", true))).total());
        assertEquals(1, query(Map.of("status", Map.of("$contains", "ET"))).total());
    }

    @Test
    void timeRangeAgentAndPaging() {
        for (int i = 0; i < 5; i++) {
            var record = record("r" + i, "{\"n\":" + i + "}", T0.plusSeconds(i));
            record.agentId = i % 2 == 0 ? "agent1" : "agent2";
            store().insert(record);
        }
        var request = new DatasetRecordService.QueryRequest(datasetId, T0.plusSeconds(1), T0.plusSeconds(3), null, 2, 1, null, null);
        var result = store().query(request);

        assertEquals(3, result.total());
        assertEquals(2, result.records().size());
        assertEquals(rid("r2"), result.records().getFirst().id);
        assertEquals(rid("r1"), result.records().get(1).id);
        assertFalse(result.truncated());

        var agentRequest = new DatasetRecordService.QueryRequest(datasetId, T0.plusSeconds(1), T0.plusSeconds(3), null, 10, 0, "agent1", null);
        assertEquals(1, store().query(agentRequest).total());
    }

    @Test
    void fieldsTrimDataAfterPaging() {
        insert("r1", "{\"status\":\"done\",\"priority\":3}", T0);
        var result = store().query(new DatasetRecordService.QueryRequest(datasetId, null, null, List.of("status"), null, null, null, null));

        var data = dataOf(result.records().getFirst());
        assertEquals(1, data.size());
        assertEquals("done", data.get("status"));
    }

    @Test
    void updateAndDeleteRequireMatchingDataset() {
        insert("r1", "{\"status\":\"done\"}", T0);

        assertFalse(store().update(datasetId + "-other", rid("r1"), Map.of("status", "x"), "u2"));
        assertFalse(store().delete(datasetId + "-other", rid("r1")));
        assertTrue(store().update(datasetId, rid("r1"), Map.of("status", "x"), "u2"));
        assertTrue(store().delete(datasetId, rid("r1")));
    }

    @Test
    void updateMergesAndDeletesFields() {
        insert("r1", "{\"name\":\"a\",\"status\":\"done\"}", T0);
        var patch = new LinkedHashMap<String, Object>();
        patch.put("status", null);
        patch.put("extra", 1);

        assertTrue(store().update(datasetId, rid("r1"), patch, "u2"));
        var record = store().query(new DatasetRecordService.QueryRequest(datasetId, null, null, null, 1, 0, null, null))
                .records().getFirst();
        var data = dataOf(record);
        assertEquals("a", data.get("name"));
        assertEquals(1, data.get("extra"));
        assertFalse(data.containsKey("status"));
        assertEquals("u2", record.updatedBy);
    }

    @Test
    void sessionStateUpsertAndPatch() {
        store().upsertBySession(datasetId, "s1", "{\"business_type\":\"QSR\"}", "agent1", "u1");
        var record = store().findBySession(datasetId, "s1").orElseThrow();
        assertEquals("QSR", dataOf(record).get("business_type"));
        assertNotNull(record.createdAt);

        store().patchBySession(datasetId, "s1", "{\"menu_source\":\"ONLINE\"}", "agent1", "u1");
        var patched = store().findBySession(datasetId, "s1").orElseThrow();
        assertEquals("QSR", dataOf(patched).get("business_type"));
        assertEquals("ONLINE", dataOf(patched).get("menu_source"));
    }

    @Test
    void patchReplacesObjectAndDropsNullsInsideIt() {
        store().upsertBySession(datasetId, "s1", "{\"meta\":5}", "agent1", "u1");
        store().patchBySession(datasetId, "s1", "{\"meta\":{\"keep\":1,\"drop\":null}}", "agent1", "u1");

        var data = dataOf(store().findBySession(datasetId, "s1").orElseThrow());
        assertEquals(1, data.size());
        @SuppressWarnings("unchecked")
        var meta = (Map<String, Object>) data.get("meta");
        assertEquals(1, meta.size());
        assertEquals(1, meta.get("keep"));
    }

    @Test
    void rejectsOversizedState() {
        var big = "{\"blob\":\"" + "x".repeat(DatasetRecordService.MAX_STATE_BYTES) + "\"}";
        assertThrows(IllegalArgumentException.class,
                () -> store().upsertBySession(datasetId, "s1", big, "agent1", "u1"));
    }
}
