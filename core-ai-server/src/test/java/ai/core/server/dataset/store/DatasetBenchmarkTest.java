package ai.core.server.dataset.store;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import core.framework.internal.db.DatabaseImpl;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Q9 benchmark: 100 datasets / 1M records, 200k in the hotspot. Enabled by CORE_AI_BENCHMARK=true so a
 * normal test run never pays for it. Prints one CSV line per scenario; the PoC report carries the table.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_BENCHMARK", matches = "true")
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class DatasetBenchmarkTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetBenchmarkTest.class);
    private static final int WARMUP = 10;
    private static final int ITERATIONS = 200;
    private static final int HOTSPOT_ROWS = 200_000;
    private static final int OTHER_DATASETS = 99;
    private static final int OTHER_ROWS = 8_000;
    private static final String[] REGIONS = {"us", "cn", "eu", "jp", "de"};
    private static final String[] STATUSES = {"done", "pending", "failed"};
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String INSERT_SQL = "INSERT INTO dataset.dataset_records (id, dataset_id, agent_id, run_id, session_id, user_id, data, "
            + "run_started_at, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)";

    private static final DatabaseImpl DATABASE = benchmarkDatabase();
    private static final MongoClient MONGO = mongoClient();
    private static final String MONGO_COLLECTION = "dataset_benchmark_" + UUID.randomUUID().toString().replace("-", "");

    private static DatabaseImpl benchmarkDatabase() {
        var database = new DatabaseImpl("dataset-benchmark");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = PgTestSupport.user();
        database.password = PgTestSupport.password();
        database.view(PgDatasetRecordRow.class);
        database.view(PgJsonRow.class);
        database.view(PgCountRow.class);
        database.view(PgDatasetViewRow.class);
        database.view(PgSizeRow.class);
        database.view(DatasetViewManagerTest.RegionRow.class);
        PgTestSupport.schema(database);
        return database;
    }

    private static MongoClient mongoClient() {
        var uri = System.getenv("CORE_AI_MONGO_TEST_URI");
        return uri != null ? MongoClients.create(uri) : null;
    }

    private static String mongoDatabase() {
        return new ConnectionString(System.getenv("CORE_AI_MONGO_TEST_URI")).getDatabase();
    }

    private static void printPlan(String label, java.sql.ResultSet resultSet) throws java.sql.SQLException {
        try (resultSet) {
            while (resultSet.next()) {
                LOGGER.info("explain {} | {}", label, resultSet.getString(1));
            }
        }
    }

    private static long percentile(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static List<SchemaField> schema() {
        var fields = new ArrayList<SchemaField>();
        fields.add(field("positive_count", SchemaFieldType.NUMBER));
        fields.add(field("negative_count", SchemaFieldType.NUMBER));
        fields.add(field("total", SchemaFieldType.NUMBER));
        fields.add(field("status", SchemaFieldType.STRING));
        fields.add(field("region", SchemaFieldType.STRING));
        fields.add(field("flagged", SchemaFieldType.BOOLEAN));
        return fields;
    }

    private static SchemaField field(String name, SchemaFieldType type) {
        var field = new SchemaField();
        field.name = name;
        field.type = type;
        return field;
    }

    private static Dataset dataset() {
        var dataset = new Dataset();
        dataset.id = "bench-hot";
        dataset.name = "benchmark";
        dataset.userId = "bench";
        dataset.schema = schema();
        return dataset;
    }

    private static DatasetRecord record(Random random) {
        return record("id-" + UUID.randomUUID(), random);
    }

    private static DatasetRecord record(String id, Random random) {
        var record = new DatasetRecord();
        record.id = id;
        record.datasetId = "bench-hot";
        record.agentId = "bench";
        record.data = data(random);
        record.runStartedAt = START;
        record.createdAt = START;
        record.createdBy = "bench";
        record.updatedAt = START;
        record.updatedBy = "bench";
        return record;
    }

    private static String data(Random random) {
        var data = new LinkedHashMap<String, Object>();
        data.put("positive_count", random.nextInt(100));
        data.put("negative_count", random.nextInt(50));
        data.put("total", random.nextInt(150));
        data.put("status", STATUSES[random.nextInt(STATUSES.length)]);
        data.put("region", REGIONS[random.nextInt(REGIONS.length)]);
        data.put("flagged", random.nextBoolean());
        return JsonUtil.toJson(data);
    }

    @AfterAll
    static void cleanup() {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("CORE_AI_PG_TEST_URL"),
                PgTestSupport.user(), PgTestSupport.password())) {
            deleteBenchmarkRows(connection);
        } catch (java.sql.SQLException e) {
            LOGGER.warn("benchmark cleanup pg failed: {}", e.getMessage());
        }
        if (MONGO != null) {
            MONGO.getDatabase(mongoDatabase()).getCollection(MONGO_COLLECTION).drop();
            MONGO.close();
        }
        DATABASE.close();
    }

    private static void deleteBenchmarkRows(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("DELETE FROM dataset.dataset_records WHERE dataset_id LIKE 'bench-%'");
        }
    }
    private final PostgresDatasetRecordStore postgres = postgresStore();
    private final MongoNativeDatasetRecordStore mongo = mongoStore();

    private PostgresDatasetRecordStore postgresStore() {
        var manager = new DatasetViewManager();
        manager.database = DATABASE;
        manager.ddl = PgTestSupport.ddl();
        var metaStore = mock(PgDatasetMetaStore.class);
        when(metaStore.get(anyString())).thenReturn(dataset());
        var store = new PostgresDatasetRecordStore();
        store.database = DATABASE;
        store.viewManager = manager;
        store.datasetMetaStore = metaStore;
        return store;
    }

    private MongoNativeDatasetRecordStore mongoStore() {
        if (MONGO == null) return null;
        var store = new MongoNativeDatasetRecordStore(MONGO, mongoDatabase(), MONGO_COLLECTION);
        store.ensureIndexes();
        return store;
    }

    @Test
    void run() {
        var started = Instant.now();
        seedPostgres();
        seedMongo();
        LOGGER.info("benchmark seed elapsed={}ms", Instant.now().toEpochMilli() - started.toEpochMilli());
        LOGGER.info("scenario,store,p50_ms,p95_ms,p99_ms");
        viewManager().sync(dataset());
        equalityScenario();
        rangeScenario();
        aggregationScenario();
        viewScenario();
        writeLatencyScenario();
        storageScenario();
    }

    private void equalityScenario() {
        var filter = Map.<String, Object>of("status", "done");
        var request = new DatasetRecordService.QueryRequest("bench-hot", null, null, null, 100, 0, null, filter);
        measure("equality(status=done);view", "postgres", () -> postgres.query(request));
        if (mongo == null) return;
        measure("equality(status=done);no-wildcard-index", "mongo-native", () -> mongo.query(request));
        wildcardIndex();
        measure("equality(status=done);wildcard-index", "mongo-native", () -> mongo.query(request));
        measure("equality(status=done);wildcard-index,no-array-guard", "mongo-raw", this::rawEquality);
    }

    private void rawEquality() {
        MongoCollection<Document> collection = MONGO.getDatabase(mongoDatabase()).getCollection(MONGO_COLLECTION);
        var filter = Filters.and(Filters.eq("dataset_id", "bench-hot"), Filters.eq("data.status", "done"));
        collection.find(filter).limit(100).into(new ArrayList<>());
        collection.countDocuments(filter);
    }

    private void rangeScenario() {
        var filter = Map.<String, Object>of("positive_count", Map.of("$gt", 50));
        var request = new DatasetRecordService.QueryRequest("bench-hot", null, null, null, 100, 0, null, filter);
        PgTestSupport.ddl().executeAll(List.of("DROP INDEX IF EXISTS ix_ds_benchhot_positive_count"));
        measure("range(positive_count>50);view,no-index", "postgres", () -> postgres.query(request));
        viewManager().indexField(dataset(), "positive_count");
        measure("range(positive_count>50);view,with-index", "postgres", () -> postgres.query(request));
        explainRange();
        if (mongo != null) measure("range(positive_count>50)", "mongo-native", () -> mongo.query(request));
    }

    private void aggregationScenario() {
        var daily = new AggregateRequest();
        daily.datasetId = "bench-hot";
        daily.bucket = "day";
        daily.timezone = "UTC";
        daily.from = START.plusDays(60);
        daily.to = START.plusDays(90);
        daily.metrics = List.of(new Metric("total", "SUM"), new Metric("positive_count", "AVG"));
        measure("daily SUM/AVG 30d", "postgres", () -> postgres.aggregate(daily));

        var byRegion = new AggregateRequest();
        byRegion.datasetId = "bench-hot";
        byRegion.bucket = "day";
        byRegion.timezone = "UTC";
        byRegion.categoryField = "region";
        byRegion.from = START.plusDays(60);
        byRegion.to = START.plusDays(90);
        byRegion.metrics = List.of(new Metric("total", "SUM"));
        measure("daily by region 30d", "postgres", () -> postgres.aggregate(byRegion));

        if (mongo != null) {
            measure("daily SUM/AVG 30d", "mongo-native", () -> mongo.aggregate(daily));
            measure("daily by region 30d", "mongo-native", () -> mongo.aggregate(byRegion));
        }
    }

    private void viewScenario() {
        var manager = new DatasetViewManager();
        manager.database = DATABASE;
        manager.ddl = PgTestSupport.ddl();
        manager.sync(dataset());
        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName("bench-hot"));
        measure("view group by region", "postgres", () -> DATABASE.select(
                "SELECT region AS value, sum(total) AS sum FROM " + view + " GROUP BY region",
                DatasetViewManagerTest.RegionRow.class));
    }

    private void writeLatencyScenario() {
        var random = new Random(7);
        measure("insert single record", "postgres", () -> postgres.insert(record(random)));
        var id = "bench-patch";
        postgres.insert(record(id, random));
        measure("patch single record", "postgres", () -> postgres.update("bench-hot", id, Map.of("total", 2), "bench"));
        if (mongo != null) {
            measure("insert single record", "mongo-native", () -> mongo.insert(record(random)));
            mongo.insert(record(id, random));
            measure("patch single record", "mongo-native", () -> mongo.update("bench-hot", id, Map.of("total", 2), "bench"));
        }
    }

    private void storageScenario() {
        vacuum();
        var size = DATABASE.selectOne("SELECT pg_total_relation_size(?::regclass) AS total", PgCountRow.class, "dataset.dataset_records")
                .map(row -> row.total).orElse(0L);
        var rows = DATABASE.selectOne("SELECT count(*) AS total FROM dataset.dataset_records WHERE dataset_id = ?", PgCountRow.class, "bench-hot")
                .map(row -> row.total).orElse(0L);
        LOGGER.info("storage postgres total_bytes=" + size + ", hotspot_rows=" + rows);
        if (mongo != null) {
            var stats = MONGO.getDatabase(mongoDatabase()).runCommand(new Document("collStats", MONGO_COLLECTION));
            LOGGER.info("storage mongo size_bytes=" + stats.get("size") + ", storage_size_bytes=" + stats.get("storageSize")
                    + ", index_bytes=" + stats.get("totalIndexSize"));
        }
    }

    private DatasetViewManager viewManager() {
        var manager = new DatasetViewManager();
        manager.database = DATABASE;
        manager.ddl = PgTestSupport.ddl();
        return manager;
    }

    private void wildcardIndex() {
        MongoCollection<Document> collection = MONGO.getDatabase(mongoDatabase()).getCollection(MONGO_COLLECTION);
        collection.createIndex(Indexes.ascending("data.$**"));
    }

    private void vacuum() {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("CORE_AI_PG_TEST_URL"),
                PgTestSupport.user(), PgTestSupport.password());
             var statement = connection.createStatement()) {
            statement.execute("VACUUM (FULL, ANALYZE) dataset.dataset_records");
        } catch (java.sql.SQLException e) {
            LOGGER.warn("benchmark vacuum failed: {}", e.getMessage());
        }
    }

    private void explainRange() {
        var column = viewManager().columns(dataset()).stream()
                .filter(candidate -> candidate.sourceName().equals("positive_count"))
                .findFirst().orElseThrow();
        var view = DatasetViewManager.qualifiedView(DatasetViewManager.viewName("bench-hot"));
        var predicate = " WHERE dataset_id = ? AND " + DatasetViewManager.quoteIdentifier(column.columnName()) + " > ?::numeric";
        explain("count", "EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM " + view + predicate);
        explain("page", "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM " + view + predicate
                + " ORDER BY run_started_at DESC, id LIMIT 100 OFFSET 0");
    }

    private void explain(String label, String sql) {
        try (var connection = java.sql.DriverManager.getConnection(System.getenv("CORE_AI_PG_TEST_URL"),
                PgTestSupport.user(), PgTestSupport.password());
             var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, "bench-hot");
            statement.setBigDecimal(2, new BigDecimal("50"));
            printPlan(label, statement.executeQuery());
        } catch (java.sql.SQLException e) {
            LOGGER.warn("benchmark explain failed: {}", e.getMessage());
        }
    }

    private void seedPostgres() {
        var count = DATABASE.selectOne("SELECT count(*) AS total FROM dataset.dataset_records WHERE dataset_id = ?", PgCountRow.class, "bench-hot")
                .map(row -> row.total).orElse(0L);
        if (count >= HOTSPOT_ROWS) {
            LOGGER.info("benchmark seed postgres reused");
            return;
        }
        var random = new Random(42);
        long seeded = 0;
        for (int dataset = 0; dataset < OTHER_DATASETS + 1; dataset++) {
            var datasetId = dataset == 0 ? "bench-hot" : "bench-" + dataset;
            int rows = dataset == 0 ? HOTSPOT_ROWS : OTHER_ROWS;
            var batch = new ArrayList<Object[]>(500);
            for (int i = 0; i < rows; i++) {
                batch.add(recordParams(datasetId, dataset + "-" + i, random));
                if (batch.size() == 500) {
                    DATABASE.batchExecute(INSERT_SQL, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) DATABASE.batchExecute(INSERT_SQL, batch);
            seeded += rows;
        }
        LOGGER.info("benchmark seed postgres=" + seeded);
        PgTestSupport.ddl().executeAll(List.of("ANALYZE dataset.dataset_records"));
    }

    private Object[] recordParams(String datasetId, String id, Random random) {
        var time = START.plusSeconds(random.nextInt(90 * 24 * 3600));
        var params = new ArrayList<>();
        params.add(datasetId + "-" + id);
        params.add(datasetId);
        params.add("agent-bench");
        params.add(null);
        params.add(null);
        params.add("bench-user");
        params.add(data(random));
        params.add(time);
        params.add(time);
        params.add("bench");
        params.add(time);
        params.add("bench");
        return params.toArray();
    }

    private void seedMongo() {
        if (mongo == null) return;
        MongoCollection<Document> collection = MONGO.getDatabase(mongoDatabase()).getCollection(MONGO_COLLECTION);
        if (collection.countDocuments() >= HOTSPOT_ROWS) {
            LOGGER.info("benchmark seed mongo reused");
            return;
        }
        var random = new Random(42);
        long seeded = 0;
        for (int dataset = 0; dataset < OTHER_DATASETS + 1; dataset++) {
            var datasetId = dataset == 0 ? "bench-hot" : "bench-" + dataset;
            int rows = dataset == 0 ? HOTSPOT_ROWS : OTHER_ROWS;
            var documents = new ArrayList<Document>(5_000);
            for (int i = 0; i < rows; i++) {
                var time = Date.from(START.plusSeconds(random.nextInt(90 * 24 * 3600)).toInstant());
                documents.add(new Document("_id", datasetId + "-" + dataset + "-" + i)
                        .append("dataset_id", datasetId)
                        .append("agent_id", "agent-bench")
                        .append("user_id", "bench-user")
                        .append("data", Document.parse(data(random)))
                        .append("run_started_at", time)
                        .append("created_at", time)
                        .append("created_by", "bench")
                        .append("updated_at", time)
                        .append("updated_by", "bench")
                        .append("version", 0L));
                if (documents.size() == 5_000) {
                    collection.insertMany(documents);
                    documents.clear();
                }
            }
            if (!documents.isEmpty()) collection.insertMany(documents);
            seeded += rows;
        }
        LOGGER.info("benchmark seed mongo=" + seeded);
    }

    private void measure(String scenario, String store, Runnable action) {
        for (int i = 0; i < WARMUP; i++) action.run();
        var samples = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long start = System.nanoTime();
            action.run();
            samples[i] = (System.nanoTime() - start) / 1_000_000;
        }
        Arrays.sort(samples);
        LOGGER.info("{},{},{},{},{}", scenario, store,
                percentile(samples, 50), percentile(samples, 95), percentile(samples, 99));
    }
}
