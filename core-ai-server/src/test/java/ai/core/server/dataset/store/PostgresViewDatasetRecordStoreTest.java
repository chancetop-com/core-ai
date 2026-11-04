package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the same contract again with a declared schema, so the filters go through the generated view
 * (and fall back to jsonb where the view cannot express them). This is the regression net for the
 * query routing: without it only the jsonb path was covered.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PostgresViewDatasetRecordStoreTest extends DatasetRecordStoreContractTest {
    private static final DatabaseImpl DATABASE = PgTestSupport.database("dataset-view-contract-test");

    private static PostgresDatasetRecordStore newStore(Map<String, Dataset> datasets) {
        var manager = PgTestSupport.viewManager(DATABASE);
        var metaStore = mock(PgDatasetMetaStore.class);
        when(metaStore.get(anyString())).thenAnswer(invocation -> datasets.get(invocation.getArgument(0)));
        var store = new PostgresDatasetRecordStore();
        store.database = DATABASE;
        store.viewManager = manager;
        store.datasetMetaStore = metaStore;
        return store;
    }

    private static Dataset dataset(String id) {
        var dataset = new Dataset();
        dataset.id = id;
        dataset.name = "contract";
        dataset.userId = "u1";
        var fields = new ArrayList<SchemaField>();
        fields.add(field("status", SchemaFieldType.STRING));
        fields.add(field("priority", SchemaFieldType.NUMBER));
        fields.add(field("score", SchemaFieldType.NUMBER));
        fields.add(field("name", SchemaFieldType.STRING));
        fields.add(field("region", SchemaFieldType.STRING));
        fields.add(field("flagged", SchemaFieldType.BOOLEAN));
        dataset.schema = fields;
        return dataset;
    }

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

    private final Map<String, Dataset> datasets = new ConcurrentHashMap<>();
    private final PostgresDatasetRecordStore store = newStore(datasets);

    @Override
    protected DatasetRecordStore store() {
        return store;
    }

    @Override
    protected void prepareDataset(String datasetId) {
        var dataset = dataset(datasetId);
        datasets.put(datasetId, dataset);
        store.viewManager.sync(dataset);
    }

    @Override
    protected void cleanupDataset(String datasetId) {
        store.viewManager.drop(datasetId);
        datasets.remove(datasetId);
    }

    @Test
    void ninMatchesRecordsMissingTheField() {
        insert("r1", "{\"region\":\"us\"}", T0);
        insert("r2", "{\"region\":\"cn\"}", T0.plusSeconds(1));
        insert("r3", "{\"note\":\"no region\"}", T0.plusSeconds(2));

        assertEquals(2, query(Map.of("region", Map.of("$nin", List.of("us")))).total());
    }

    @Test
    void nullConditionsFallBackToJsonbSoTypeMismatchIsNotTreatedAsNull() {
        insert("r1", "{\"score\":\"abc\"}", T0);
        insert("r2", "{\"score\":5}", T0.plusSeconds(1));
        insert("r3", "{}", T0.plusSeconds(2));

        var nullFilter = new LinkedHashMap<String, Object>();
        nullFilter.put("score", null);
        var missing = query(nullFilter);
        assertEquals(1, missing.total());
        assertEquals(rid("r3"), missing.records().getFirst().id);

        var ne = new HashMap<String, Object>();
        ne.put("$ne", null);
        assertEquals(2, query(Map.of("score", ne)).total());
    }

    @Test
    void stringColumnIgnoresNonStringValues() {
        insert("r1", "{\"status\":\"3\"}", T0);
        insert("r2", "{\"status\":3}", T0.plusSeconds(1));

        assertEquals(1, query(Map.of("status", "3")).total());
    }
}
