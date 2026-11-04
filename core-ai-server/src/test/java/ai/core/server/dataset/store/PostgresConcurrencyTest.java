package ai.core.server.dataset.store;

import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PostgresConcurrencyTest extends DatasetRecordStoreConcurrencyTest {
    private static final DatabaseImpl DATABASE = PgTestSupport.database("dataset-concurrency-test");

    private static PostgresDatasetRecordStore newStore() {
        var manager = PgTestSupport.viewManager(DATABASE);
        var store = new PostgresDatasetRecordStore();
        store.database = DATABASE;
        store.viewManager = manager;
        return store;
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private final PostgresDatasetRecordStore store = newStore();

    @Override
    protected DatasetRecordStore store() {
        return store;
    }

}
