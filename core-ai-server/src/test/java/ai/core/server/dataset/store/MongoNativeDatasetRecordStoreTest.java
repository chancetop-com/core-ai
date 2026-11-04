package ai.core.server.dataset.store;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Runs the shared contract against a real Mongo through the native driver. Enabled by
 * CORE_AI_MONGO_TEST_URI; each run uses a random collection and drops it afterwards.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_MONGO_TEST_URI", matches = ".+")
class MongoNativeDatasetRecordStoreTest extends DatasetRecordStoreContractTest {
    private static final String URI = System.getenv("CORE_AI_MONGO_TEST_URI");
    private static final String DATABASE = new ConnectionString(URI).getDatabase();
    private static final String COLLECTION = "dataset_records_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final MongoClient CLIENT = MongoClients.create(URI);

    private static ZonedDateTime at(int dayOffset) {
        return T0.plusDays(dayOffset);
    }

    private static List<Object> row(List<List<Object>> rows, String category) {
        return rows.stream().filter(row -> category.equals(row.get(1))).findFirst().orElseThrow();
    }

    private static BigDecimal number(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

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


    @Test
    void aggregateBucketsByDayAndCategory() {
        insertFor("r1", "{\"total\":10,\"positive_count\":4,\"region\":\"us\"}", at(0));
        insertFor("r2", "{\"total\":20,\"positive_count\":6,\"region\":\"us\"}", at(0));
        insertFor("r3", "{\"total\":5,\"positive_count\":1,\"region\":\"cn\"}", at(1));
        insertFor("r4", "{\"total\":7,\"positive_count\":3,\"region\":\"eu\"}", at(2));

        var request = new AggregateRequest();
        request.datasetId = datasetId();
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
        insertFor("r1", "{\"total\":10,\"region\":\"us\"}", at(0));
        insertFor("r2", "{\"total\":15,\"region\":\"us\"}", at(1));

        var request = new AggregateRequest();
        request.datasetId = datasetId();
        request.categoryField = "region";
        request.metrics = List.of(new Metric("total", "SUM"));

        var result = store.aggregate(request);
        assertEquals(1, result.rows().size());
        assertNull(result.rows().getFirst().get(0));
        assertEquals(0, new BigDecimal("25").compareTo(number(result.rows().getFirst().get(2))));
    }

    @Test
    void lastMetricTakesTheNewestRun() {
        insertFor("r1", "{\"status\":\"old\"}", at(0));
        insertFor("r2", "{\"status\":\"new\"}", at(1));

        var request = new AggregateRequest();
        request.datasetId = datasetId();
        request.metrics = List.of(new Metric("status", "LAST"));

        var result = store.aggregate(request);
        assertEquals("new", result.rows().getFirst().get(2));
    }

    private void insertFor(String id, String data, ZonedDateTime time) {
        var record = record(id, data, time);
        store.insert(record);
    }
}
