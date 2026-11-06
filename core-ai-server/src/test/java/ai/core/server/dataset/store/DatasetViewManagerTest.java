package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import core.framework.db.Column;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * View generation rules: column naming, type mapping, rebuild on schema change, and injection resistance
 * for hostile field names.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class DatasetViewManagerTest {
    private static final DatabaseImpl DATABASE = PgTestSupport.database("dataset-view-test");

    private static SchemaField field(String name, SchemaFieldType type) {
        var field = new SchemaField();
        field.name = name;
        field.type = type;
        return field;
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private final List<String> created = new ArrayList<>();
    private final DatasetViewManager manager = manager();
    private final PostgresDatasetRecordStore store = store();

    private Dataset dataset(String id, SchemaField... fields) {
        var dataset = new Dataset();
        dataset.id = id;
        dataset.name = "test";
        dataset.userId = "u1";
        dataset.schema = new ArrayList<>(List.of(fields));
        created.add(id);
        return dataset;
    }

    private DatasetViewManager manager() {
        var manager = new DatasetViewManager();
        manager.ddl = PgTestSupport.ddl();
        return manager;
    }

    private PostgresDatasetRecordStore store() {
        var store = new PostgresDatasetRecordStore();
        store.database = DATABASE;
        store.viewManager = manager;
        return store;
    }


    @AfterEach
    void dropCreatedViews() {
        for (var id : created) manager.drop(id);
        created.clear();
    }

    @BeforeEach
    void ensureSchema() {
        PgTestSupport.schema();
    }

    @Test
    void generatesStableColumnNamesForHostileFieldNames() {
        var dataset = dataset("ds-" + UUID.randomUUID(),
                field("Order Count", SchemaFieldType.NUMBER),
                field("1st", SchemaFieldType.NUMBER),
                field("id", SchemaFieldType.STRING),
                field("select", SchemaFieldType.STRING),
                field("好评数", SchemaFieldType.NUMBER),
                field("a-b", SchemaFieldType.STRING),
                field("a_b", SchemaFieldType.STRING),
                field("a'b\"c; DROP TABLE dataset_records; --", SchemaFieldType.STRING));

        var plan = manager.plan(dataset);
        var names = plan.columns().stream().map(DatasetViewManager.Column::columnName).toList();

        assertEquals("order_count", names.get(0));
        assertEquals("f_1st", names.get(1));
        assertEquals("f_id", names.get(2));
        assertEquals("f_select", names.get(3));
        assertEquals("a_b", names.get(5));
        assertEquals("a_b_2", names.get(6));
        assertTrue(names.get(4).startsWith("f_"));
        assertEquals(names.size(), names.stream().distinct().count());
        assertTrue(plan.statements().get(1).contains("a''b\"c; DROP TABLE dataset_records; --"));
        assertTrue(plan.viewName().startsWith("ds_"));
        assertTrue(plan.viewName().length() <= 63);
    }

    @Test
    void createsQueryableViewAndRebuildsOnTypeChange() {
        var dataset = dataset("ds-" + UUID.randomUUID(),
                field("region", SchemaFieldType.STRING),
                field("total", SchemaFieldType.NUMBER));
        insert(dataset.id, "{\"region\":\"us\",\"total\":10}");
        insert(dataset.id, "{\"region\":\"us\",\"total\":5}");
        insert(dataset.id, "{\"region\":\"cn\",\"total\":7}");

        manager.sync(dataset);
        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id));
        var rows = DATABASE.select("SELECT region AS value, sum(total) AS sum FROM " + view + " GROUP BY region ORDER BY region",
                RegionRow.class);
        assertEquals(2, rows.size());
        assertEquals("cn", rows.get(0).value);
        assertEquals(0, new BigDecimal("7").compareTo(rows.get(0).sum));

        dataset.schema.get(1).type = SchemaFieldType.STRING;
        manager.sync(dataset);
        // a string column only accepts JSON strings now; the numeric value from before is NULL
        var numeric = DATABASE.selectOne("SELECT total AS value FROM " + view + " WHERE region = ?", ValueRow.class, "cn");
        assertNull(numeric.orElseThrow().value);

        insert(dataset.id, "{\"region\":\"cn\",\"total\":\"seven\"}");
        var text = DATABASE.selectOne("SELECT total AS value FROM " + view + " WHERE region = ? AND total IS NOT NULL", ValueRow.class, "cn");
        assertEquals("seven", text.orElseThrow().value);
    }

    @Test
    void removesFieldsMissingFromTheNewSchema() {
        var dataset = dataset("ds-" + UUID.randomUUID(), field("region", SchemaFieldType.STRING), field("total", SchemaFieldType.NUMBER));
        manager.sync(dataset);

        dataset.schema = new ArrayList<>(List.of(field("region", SchemaFieldType.STRING)));
        manager.sync(dataset);

        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id));
        assertThrows(RuntimeException.class, () -> DATABASE.select("SELECT total AS value FROM " + view, ValueRow.class));
    }

    @Test
    void dropRemovesTheViewAndRegistryRow() {
        var dataset = dataset("ds-" + UUID.randomUUID(), field("region", SchemaFieldType.STRING));
        manager.sync(dataset);
        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id));
        assertEquals(0, DATABASE.select("SELECT region AS value FROM " + view, ValueRow.class).size());

        manager.drop(dataset.id);
        assertThrows(RuntimeException.class, () -> DATABASE.select("SELECT region AS value FROM " + view, ValueRow.class));
        assertTrue(DATABASE.select("SELECT dataset_id AS value FROM dataset_views WHERE dataset_id = ?", ValueRow.class, dataset.id).isEmpty());
    }

    @Test
    void specialCharactersInFieldNamesStillProduceAQueryableView() {
        var dataset = dataset("ds-" + UUID.randomUUID(),
                field("a'b\"c; DROP TABLE dataset_records; --", SchemaFieldType.STRING),
                field("好评数", SchemaFieldType.NUMBER),
                field("1st", SchemaFieldType.STRING));
        insert(dataset.id, "{\"a'b\\\"c; DROP TABLE dataset_records; --\":\"x\",\"好评数\":3,\"1st\":\"y\"}");
        manager.sync(dataset);

        var plan = manager.plan(dataset);
        var view = DatasetViewManager.qualifiedView(plan.viewName());
        var rows = DATABASE.select("SELECT " + DatasetViewManager.quoteIdentifier(plan.columns().get(1).columnName())
                + " AS value FROM " + view, ValueRow.class);
        assertEquals("3", rows.getFirst().value);
    }

    private void insert(String datasetId, String data) {
        var record = new DatasetRecord();
        record.id = UUID.randomUUID().toString();
        record.datasetId = datasetId;
        record.agentId = "agent1";
        record.data = data;
        record.runStartedAt = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        record.createdAt = record.runStartedAt;
        record.createdBy = "u1";
        record.updatedAt = record.runStartedAt;
        record.updatedBy = "u1";
        store.insert(record);
    }

    public static class ValueRow {
        @Column(name = "value")
        public String value;
    }

    public static class RegionRow {
        @Column(name = "value")
        public String value;

        @Column(name = "sum")
        public BigDecimal sum;
    }
}
