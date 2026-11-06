package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the read-only executor can only see the datasets it was granted, that writes and
 * long-running statements are refused, and that the role cannot be escalated from inside the query.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class DatasetSqlExecutorTest {
    private static final String READER = "dataset_reader_test";
    private static final String READER_PASSWORD = "reader-test-password";
    private static final DatabaseImpl DATABASE = PgTestSupport.database("dataset-sql-test");
    private static final DatasetSqlExecutor EXECUTOR = new DatasetSqlExecutor(System.getenv("CORE_AI_PG_TEST_URL"),
            System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
            System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"), READER, READER_PASSWORD);

    private static DatasetViewManager manager() {
        return PgTestSupport.viewManager();
    }

    private static PostgresDatasetRecordStore newStore() {
        var store = new PostgresDatasetRecordStore();
        store.database = DATABASE;
        store.viewManager = manager();
        return store;
    }

    private static String view(Dataset dataset) {
        return DatasetViewManager.qualifiedView(DatasetViewManager.viewName(dataset.id));
    }

    private static Dataset dataset() {
        var dataset = new Dataset();
        dataset.id = "ds-" + UUID.randomUUID();
        dataset.name = "test";
        dataset.userId = "u1";
        dataset.schema = new ArrayList<>(List.of(field("region", SchemaFieldType.STRING), field("total", SchemaFieldType.NUMBER)));
        return dataset;
    }

    private static SchemaField field(String name, SchemaFieldType type) {
        var field = new SchemaField();
        field.name = name;
        field.type = type;
        return field;
    }

    @BeforeAll
    static void initializeExecutor() {
        EXECUTOR.initialize();
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private final DatasetViewManager manager = PgTestSupport.viewManager();
    private final PostgresDatasetRecordStore store = newStore();



    @Test
    void readsOnlyTheGrantedDataset() {
        var first = dataset();
        var second = dataset();
        manager.sync(first);
        manager.sync(second);
        insert(first.id, "{\"region\":\"us\",\"total\":1}");
        insert(second.id, "{\"region\":\"eu\",\"total\":2}");

        var result = EXECUTOR.execute("SELECT region, total FROM " + view(first), List.of(first.id));
        assertEquals(List.of("region", "total"), result.columns());
        assertEquals(1, result.rows().size());
        assertEquals("us", result.rows().getFirst().get(0));

        var denied = EXECUTOR.execute("SELECT region FROM " + view(second), List.of(first.id));
        assertTrue(denied.rows().isEmpty());
    }

    @Test
    void baseTableIsFilteredByRowLevelSecurity() {
        var first = dataset();
        manager.sync(first);
        insert(first.id, "{\"region\":\"us\",\"total\":1}");

        var granted = EXECUTOR.execute("SELECT id FROM dataset.dataset_records", List.of(first.id));
        assertEquals(1, granted.rows().size());

        var denied = EXECUTOR.execute("SELECT id FROM dataset.dataset_records", List.of());
        assertTrue(denied.rows().isEmpty());
    }

    @Test
    void runsAsTheReaderRole() {
        var result = EXECUTOR.execute("SELECT current_user AS me", List.of());
        assertEquals(READER, result.rows().getFirst().get(0));
    }

    @Test
    void grantsTableIsScopedToTheCurrentToken() {
        var first = dataset();
        var second = dataset();
        var result = EXECUTOR.execute("SELECT token FROM dataset.dataset_sql_grants", List.of(first.id, second.id));
        assertEquals(2, result.rows().size());
        assertEquals(1, result.rows().stream().map(List::getFirst).distinct().count());
    }

    @Test
    void rejectsWritesLongRunningQueriesAndMultipleStatements() {
        assertThrows(IllegalStateException.class,
                () -> EXECUTOR.execute("INSERT INTO dataset.dataset_records (id, dataset_id, agent_id, data, run_started_at, created_at, created_by, updated_at, updated_by) "
                        + "VALUES (1, 2, 3, 4, 5, 6, 7, 8, 9)", List.of()));
        assertThrows(IllegalStateException.class, () -> EXECUTOR.execute("SELECT pg_sleep(6)", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> EXECUTOR.execute("SELECT 1; DROP TABLE dataset.dataset_records", List.of()));
        assertThrows(IllegalStateException.class, () -> EXECUTOR.execute("COPY dataset.dataset_records TO STDOUT", List.of()));
    }

    @Test
    void cannotEscalateRoleFromInsideTheQuery() {
        assertThrows(IllegalStateException.class,
                () -> EXECUTOR.execute("SELECT set_config('role', 'core_ai', true)", List.of()));
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
}
