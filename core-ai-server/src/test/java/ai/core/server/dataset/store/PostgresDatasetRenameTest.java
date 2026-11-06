package ai.core.server.dataset.store;

import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The PostgreSQL rewrite moves the jsonb key in place; a second run is a no-op so a retry after a metadata
 * failure is safe.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PostgresDatasetRenameTest {
    private static final DatabaseImpl DATABASE = PgTestSupport.database("dataset-rename-test");

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private final PostgresDatasetRecordStore store = store();

    @BeforeEach
    void ensureSchema() {
        PgTestSupport.schema();
    }

    @Test
    void renamesTheFieldInEveryRecordAndIsIdempotent() {
        var datasetId = UUID.randomUUID().toString();
        insert(datasetId, "{\"old\":10,\"keep\":\"a\"}");
        insert(datasetId, "{\"old\":20,\"keep\":\"b\"}");

        assertEquals(2L, store.renameField(datasetId, "old", "new", "u1"));

        var records = DATABASE.select("SELECT id, data::text AS data FROM dataset.dataset_records WHERE dataset_id = ?",
                PgDatasetRecordRow.class, datasetId);
        assertEquals(2, records.size());
        for (var record : records) {
            var data = JsonUtil.toMap(record.data);
            assertFalse(data.containsKey("old"));
            assertTrue(data.containsKey("new"));
            assertTrue(data.containsKey("keep"));
        }

        assertEquals(0L, store.renameField(datasetId, "old", "new", "u1"));
        store.deleteByDataset(datasetId);
    }

    private void insert(String datasetId, String data) {
        var record = new DatasetRecord();
        record.id = UUID.randomUUID().toString();
        record.datasetId = datasetId;
        record.agentId = "agent";
        record.data = data;
        record.runStartedAt = ZonedDateTime.now();
        record.createdAt = record.runStartedAt;
        record.createdBy = "u1";
        record.updatedAt = record.runStartedAt;
        record.updatedBy = "u1";
        store.insert(record);
    }

    private PostgresDatasetRecordStore store() {
        var store = new PostgresDatasetRecordStore();
        store.database = DATABASE;
        store.viewManager = PgTestSupport.viewManager();
        store.datasetMetaStore = mock(PgDatasetMetaStore.class);
        return store;
    }
}
