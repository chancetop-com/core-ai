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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reconciler is the recovery path for a missing view or a metadata change that committed without its
 * view rebuild; both cases must be repaired without touching healthy datasets.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class DatasetViewReconcilerTest {
    private static final DatabaseImpl DATABASE = PgTestSupport.database("dataset-reconcile-test");

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
    private final DatasetViewReconciler reconciler = reconciler();

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
    void rebuildsAMissingView() {
        var dataset = dataset(field("region", SchemaFieldType.STRING));
        metaStore.createWithSchema(dataset);
        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id));
        PgTestSupport.ddl().executeAll(List.of("DROP VIEW " + view));

        reconciler.reconcile();

        assertEquals(0, DATABASE.select("SELECT region AS value FROM " + view, DatasetViewManagerTest.ValueRow.class).size());
    }

    @Test
    void rebuildsAStaleViewAfterAMetadataOnlyUpdate() {
        var dataset = dataset(field("region", SchemaFieldType.STRING));
        metaStore.createWithSchema(dataset);

        dataset.schema = List.of(field("region", SchemaFieldType.STRING), field("total", SchemaFieldType.NUMBER));
        dataset.updatedAt = ZonedDateTime.now();
        metaStore.update(dataset);

        reconciler.reconcile();

        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id));
        assertEquals(0, DATABASE.select("SELECT total AS value FROM " + view, DatasetViewManagerTest.ValueRow.class).size());
    }

    private Dataset dataset(SchemaField... fields) {
        var dataset = new Dataset();
        dataset.id = UUID.randomUUID().toString();
        dataset.name = "reconcile-test";
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

    private DatasetViewReconciler reconciler() {
        var reconciler = new DatasetViewReconciler();
        reconciler.ddl = PgTestSupport.ddl();
        reconciler.viewManager = viewManager;
        reconciler.metaStore = metaStore;
        return reconciler;
    }

}
