package ai.core.server.dataset.store;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_MONGO_TEST_URI", matches = ".+")
class MongoNativeConcurrencyTest extends DatasetRecordStoreConcurrencyTest {
    private static final String URI = System.getenv("CORE_AI_MONGO_TEST_URI");
    private static final String DATABASE = new ConnectionString(URI).getDatabase();
    private static final String COLLECTION = "dataset_concurrency_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final MongoClient CLIENT = MongoClients.create(URI);

    private static MongoNativeDatasetRecordStore initializedStore() {
        var store = new MongoNativeDatasetRecordStore(CLIENT, DATABASE, COLLECTION);
        store.ensureIndexes();
        return store;
    }

    @AfterAll
    static void dropCollection() {
        CLIENT.getDatabase(DATABASE).getCollection(COLLECTION).drop();
        CLIENT.close();
    }

    private final MongoNativeDatasetRecordStore store = initializedStore();

    @Override
    protected DatasetRecordStore store() {
        return store;
    }
}
