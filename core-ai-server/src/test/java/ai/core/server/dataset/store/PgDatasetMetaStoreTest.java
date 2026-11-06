package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Metadata and view changes must commit or roll back together; a failed view rebuild cannot leave the
 * dataset with a schema that no view reflects.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgDatasetMetaStoreTest {
    private static final DatabaseImpl DATABASE = PgTestSupport.database("dataset-meta-test");

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
    private final DatasetViewManager viewManager = PgTestSupport.viewManager();
    private final PgDatasetMetaStore metaStore = metaStore();

    @BeforeEach
    void ensureSchema() {
        PgTestSupport.schema();
    }

    @AfterEach
    void cleanup() {
        for (var id : created) {
            viewManager.drop(id);
            metaStore.delete(id);
        }
        created.clear();
    }

    @Test
    void createWithSchemaCreatesMetadataAndViewInOneCall() {
        var dataset = dataset(field("region", SchemaFieldType.STRING));

        metaStore.createWithSchema(dataset);

        assertNotNull(metaStore.get(dataset.id));
        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id));
        assertEquals(0, DATABASE.select("SELECT region AS value FROM " + view, DatasetViewManagerTest.ValueRow.class).size());
        assertFalse(DATABASE.select("SELECT dataset_id AS value FROM app.dataset_views WHERE dataset_id = ?",
                DatasetViewManagerTest.ValueRow.class, dataset.id).isEmpty());
    }

    @Test
    void updateWithSchemaRebuildsTheView() {
        var dataset = dataset(field("region", SchemaFieldType.STRING));
        metaStore.createWithSchema(dataset);

        dataset.schema = List.of(field("region", SchemaFieldType.STRING), field("total", SchemaFieldType.NUMBER));
        dataset.updatedAt = ZonedDateTime.now();
        metaStore.updateWithSchema(dataset);

        var row = DATABASE.selectOne("SELECT id, schema_version FROM app.datasets WHERE id = ?", PgDatasetRow.class, dataset.id)
                .orElseThrow();
        assertEquals(2, row.schemaVersion);
        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id));
        assertEquals(0, DATABASE.select("SELECT total AS value FROM " + view, DatasetViewManagerTest.ValueRow.class).size());
    }

    @Test
    void failedViewRebuildRollsBackTheMetadataChange() {
        var dataset = dataset(field("region", SchemaFieldType.STRING));
        metaStore.createWithSchema(dataset);

        dataset.schema = List.of(field("region", SchemaFieldType.STRING), field("total", SchemaFieldType.NUMBER));
        dataset.updatedAt = ZonedDateTime.now();
        metaStore.updateWithSchema(dataset);

        var before = DATABASE.selectOne("SELECT id, schema_version FROM app.datasets WHERE id = ?", PgDatasetRow.class, dataset.id)
                .orElseThrow();
        var failing = new DatasetViewManager() {
            @Override
            void sync(Connection connection, Dataset value) {
                throw new IllegalStateException("view rebuild failed");
            }
        };
        metaStore.viewManager = failing;
        try {
            dataset.schema = List.of(field("region", SchemaFieldType.STRING));
            dataset.updatedAt = ZonedDateTime.now();
            assertThrows(IllegalStateException.class, () -> metaStore.updateWithSchema(dataset));
        } finally {
            metaStore.viewManager = viewManager;
        }

        var after = DATABASE.selectOne("SELECT id, schema_version FROM app.datasets WHERE id = ?", PgDatasetRow.class, dataset.id)
                .orElseThrow();
        assertEquals(before.schemaVersion, after.schemaVersion);
        assertEquals(2, metaStore.get(dataset.id).schema.size());
    }

    private Dataset dataset(SchemaField... fields) {
        var dataset = new Dataset();
        dataset.id = UUID.randomUUID().toString();
        dataset.name = "meta-test";
        dataset.userId = "u1";
        dataset.schema = List.of(fields);
        dataset.createdAt = ZonedDateTime.now();
        dataset.updatedAt = dataset.createdAt;
        created.add(dataset.id);
        return dataset;
    }

    private PgDatasetMetaStore metaStore() {
        var store = new PgDatasetMetaStore();
        store.database = DATABASE;
        store.ddl = PgTestSupport.ddl();
        store.viewManager = viewManager;
        return store;
    }

}
