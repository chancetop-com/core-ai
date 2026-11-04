package ai.core.server.dataset.store;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the shared contract against PostgreSQL. Enabled by CORE_AI_PG_TEST_URL, so the default build
 * skips it; docker compose up postgres provides the matching local instance.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PostgresDatasetRecordStoreTest extends DatasetRecordStoreContractTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("dataset-poc-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDatasetRecordRow.class);
        database.view(PgJsonRow.class);
        database.view(PgCountRow.class);
        database.view(PgDatasetViewRow.class);
        database.view(PgSizeRow.class);
        initializeSchema(database);
        return database;
    }

    private static void initializeSchema(Database database) {
        PgTestSupport.schema(database);
    }

    private static PostgresDatasetRecordStore newStore() {
        var metaStore = mock(DatasetMetaStore.class);
        when(metaStore.get(anyString())).thenReturn(null);
        var manager = new DatasetViewManager();
        manager.database = DATABASE;
        manager.ddl = PgTestSupport.ddl();
        var store = new PostgresDatasetRecordStore();
        store.database = DATABASE;
        store.datasetMetaStore = metaStore;
        store.viewManager = manager;
        return store;
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private static ZonedDateTime at(int dayOffset) {
        return T0.plusDays(dayOffset);
    }

    private static List<Object> row(List<List<Object>> rows, String category) {
        return rows.stream().filter(row -> category.equals(row.get(1))).findFirst().orElseThrow();
    }

    private static BigDecimal number(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static Dataset dataset() {
        var dataset = new Dataset();
        dataset.id = "ds-contract";
        dataset.name = "test";
        dataset.userId = "u1";
        var fields = new ArrayList<SchemaField>();
        fields.add(field("total", SchemaFieldType.NUMBER));
        fields.add(field("positive_count", SchemaFieldType.NUMBER));
        fields.add(field("region", SchemaFieldType.STRING));
        dataset.schema = fields;
        return dataset;
    }

    private static SchemaField field(String name, SchemaFieldType type) {
        var field = new SchemaField();
        field.name = name;
        field.type = type;
        return field;
    }


    private final PostgresDatasetRecordStore store = newStore();

    @BeforeEach
    void initializeSchemaAgain() {
        initializeSchema(DATABASE);
    }

    @Override
    protected DatasetRecordStore store() {
        return store;
    }

    @Test
    void aggregateBucketsByDayAndCategory() {
        var dataset = dataset();
        dataset.id = "ds-agg-" + System.nanoTime();
        when(store.datasetMetaStore.get(dataset.id)).thenReturn(dataset);

        insertFor(dataset.id, "r1", "{\"total\":10,\"positive_count\":4,\"region\":\"us\"}", at(0));
        insertFor(dataset.id, "r2", "{\"total\":20,\"positive_count\":6,\"region\":\"us\"}", at(0));
        insertFor(dataset.id, "r3", "{\"total\":5,\"positive_count\":1,\"region\":\"cn\"}", at(1));
        insertFor(dataset.id, "r4", "{\"total\":7,\"positive_count\":3,\"region\":\"eu\"}", at(2));

        var request = new AggregateRequest();
        request.datasetId = dataset.id;
        request.bucket = "day";
        request.timezone = "UTC";
        request.categoryField = "region";
        request.metrics = List.of(new Metric("total", "SUM"), new Metric("positive_count", "AVG"), new Metric("total", "COUNT"));

        var result = store.aggregate(request);

        assertEquals(List.of("bucket", "category", "sum_total", "avg_positive_count", "count"), result.columns());
        assertEquals(3, result.rows().size());
        var us = row(result.rows(), "us");
        assertEquals("2026-01-01T00:00:00Z", us.get(0));
        assertEquals(0, new BigDecimal("30").compareTo(number(us.get(2))));
        assertEquals(0, new BigDecimal("5").compareTo(number(us.get(3))));
        assertEquals(2, ((Number) us.get(4)).intValue());
    }

    @Test
    void aggregateWithoutBucketGroupsByCategoryOnly() {
        var dataset = dataset();
        dataset.id = "ds-agg-nobucket-" + System.nanoTime();
        when(store.datasetMetaStore.get(dataset.id)).thenReturn(dataset);
        insertFor(dataset.id, "r1", "{\"total\":10,\"region\":\"us\"}", at(0));
        insertFor(dataset.id, "r2", "{\"total\":15,\"region\":\"us\"}", at(1));

        var request = new AggregateRequest();
        request.datasetId = dataset.id;
        request.categoryField = "region";
        request.metrics = List.of(new Metric("total", "SUM"));

        var result = store.aggregate(request);
        assertEquals(1, result.rows().size());
        assertNull(result.rows().getFirst().get(0));
        assertEquals(0, new BigDecimal("25").compareTo(number(result.rows().getFirst().get(2))));
    }

    @Test
    void aggregateValidationRejectsNumericFunctionOnStringField() {
        var dataset = dataset();
        dataset.id = "ds-agg-invalid";
        when(store.datasetMetaStore.get(dataset.id)).thenReturn(dataset);

        var request = new AggregateRequest();
        request.datasetId = dataset.id;
        request.metrics = List.of(new Metric("region", "SUM"));

        var error = assertThrows(IllegalArgumentException.class, () -> store.aggregate(request));
        assertEquals("function SUM requires a NUMBER field: region", error.getMessage());
    }

    @Test
    void patchBySessionRejectsOversizedMergedState() {
        store.upsertBySession(datasetId(), "s-big", "{\"keep\":1}", "agent1", "u1");
        var patch = "{\"blob\":\"" + "x".repeat(DatasetRecordService.MAX_STATE_BYTES) + "\"}";

        assertThrows(IllegalArgumentException.class,
                () -> store.patchBySession(datasetId(), "s-big", patch, "agent1", "u1"));

        var state = store.findBySession(datasetId(), "s-big").orElseThrow();
        assertEquals(1, JsonUtil.toMap(state.data).size());
    }

    @Test
    void queriesSchemaFieldsThroughTheGeneratedView() {
        var dataset = dataset();
        dataset.id = "ds-view-" + System.nanoTime();
        when(store.datasetMetaStore.get(dataset.id)).thenReturn(dataset);
        store.viewManager.sync(dataset);
        insertFor(dataset.id, "r1", "{\"total\":10,\"positive_count\":4,\"region\":\"us\"}", at(0));
        insertFor(dataset.id, "r2", "{\"total\":20,\"positive_count\":6,\"region\":\"cn\"}", at(1));

        var byRegion = store.query(new DatasetRecordService.QueryRequest(dataset.id, null, null, null, 10, 0, null, Map.of("region", "us")));
        assertEquals(1, byRegion.total());
        assertEquals("us", JsonUtil.toMap(byRegion.records().getFirst().data).get("region"));

        var byNumber = store.query(new DatasetRecordService.QueryRequest(dataset.id, null, null, null, 10, 0, null,
                Map.of("positive_count", Map.of("$gte", 5))));
        assertEquals(1, byNumber.total());
    }

    @Test
    void fallsBackToJsonbForFieldsOutsideTheSchema() {
        var dataset = dataset();
        dataset.id = "ds-fallback-" + System.nanoTime();
        when(store.datasetMetaStore.get(dataset.id)).thenReturn(dataset);
        store.viewManager.sync(dataset);
        insertFor(dataset.id, "r1", "{\"region\":\"us\",\"meta\":{\"priority\":3}}", at(0));
        insertFor(dataset.id, "r2", "{\"region\":\"us\",\"meta\":{\"priority\":4}}", at(1));

        var result = store.query(new DatasetRecordService.QueryRequest(dataset.id, null, null, null, 10, 0, null,
                Map.of("meta.priority", 3)));
        assertEquals(1, result.total());
        @SuppressWarnings("unchecked")
        var meta = (Map<String, Object>) JsonUtil.toMap(result.records().getFirst().data).get("meta");
        assertEquals(3, meta.get("priority"));
    }

    private void insertFor(String datasetId, String id, String data, ZonedDateTime time) {
        var record = record(id, data, time);
        record.datasetId = datasetId;
        store.insert(record);
    }
}
