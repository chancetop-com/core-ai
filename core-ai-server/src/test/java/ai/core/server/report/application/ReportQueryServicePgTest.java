package ai.core.server.report.application;

import ai.core.server.dataset.store.PgJsonRow;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetType;
import ai.core.server.domain.SchemaField;
import ai.core.server.domain.SchemaFieldType;
import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportData;
import ai.core.server.report.domain.ReportSpec;
import ai.core.server.report.store.PgReportInvalidCountRow;
import ai.core.server.report.store.PgReportRangeRow;
import ai.core.server.report.store.PgReportRecordRow;
import ai.core.utils.JsonUtil;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requires a PostgreSQL database that already has app.datasets and dataset.dataset_records.
 * Set CORE_AI_PG_TEST_URL to run it; no other test depends on that environment.
 *
 * @author core-ai
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class ReportQueryServicePgTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("report-query-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgJsonRow.class);
        database.view(PgReportRangeRow.class);
        database.view(PgReportInvalidCountRow.class);
        database.view(PgReportRecordRow.class);
        createSchema();
        return database;
    }

    private static void createSchema() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE SCHEMA IF NOT EXISTS dataset");
            statement.execute("CREATE TABLE IF NOT EXISTS app.datasets (id text PRIMARY KEY, name text, description text, "
                    + "user_id text, type text, schema_json jsonb, schema_version integer, created_at timestamptz, updated_at timestamptz)");
            statement.execute("CREATE TABLE IF NOT EXISTS dataset.dataset_records (id text PRIMARY KEY, dataset_id text, agent_id text, "
                    + "run_id text, session_id text, user_id text, data jsonb, run_started_at timestamptz, created_at timestamptz, "
                    + "created_by text, updated_at timestamptz, updated_by text)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize report query test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private final ReportQueryService service = new ReportQueryService();
    private final String datasetId = "report-test-" + UUID.randomUUID();
    private final String userId = "report-user-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service.database = DATABASE;
    }

    @AfterEach
    void cleanup() {
        DATABASE.execute("DELETE FROM dataset.dataset_records WHERE dataset_id = ?", datasetId);
        DATABASE.execute("DELETE FROM app.datasets WHERE id = ?", datasetId);
    }

    @Test
    void aggregatesBucketsAndRatioOfSums() {
        insertDataset();
        insertRecord("2026-11-16T16:00:00Z", 2, 5);
        insertRecord("2026-11-17T16:00:00Z", 8, 10);

        var data = service.query(new ReportQueryService.QueryRequest("report-1", 1, 1, spec(), dataset(),
                ZonedDateTime.parse("2026-11-19T01:00:00Z"), null, 100, 0));

        assertEquals(ReportConstants.STATUS_READY, data.status);
        assertEquals(2, data.rows.size());
        assertEquals(2, data.matchedRecordCount);
        assertEquals("2", data.rows.get(0).get(1));
        assertEquals("5", data.rows.get(0).get(2));
        assertEquals("0.4", data.rows.get(0).get(3));
        assertEquals("8", data.rows.get(1).get(1));
        assertEquals("10", data.rows.get(1).get(2));
        assertEquals("0.8", data.rows.get(1).get(3));
        assertEquals("2026-11-17T16:00:00Z", data.sourceTime);
        assertNotNull(data.fromInclusive);
        assertNotNull(data.toExclusive);
    }

    @Test
    void recordsModeReturnsRowsAndHonorsPagination() {
        insertDataset();
        insertRecord("2026-11-16T16:00:00Z", 2, 5);
        insertRecord("2026-11-17T16:00:00Z", 8, 10);

        var spec = spec();
        spec.query.mode = ReportConstants.MODE_RECORDS;
        spec.query.measures = null;
        spec.query.derivedMeasures = null;
        spec.query.bucket = ReportConstants.BUCKET_NONE;
        spec.query.recordFields = List.of("positive_count", "total");
        spec.display.type = ReportConstants.DISPLAY_TABLE;
        spec.display.series = null;

        var data = service.query(new ReportQueryService.QueryRequest("report-1", 1, 1, spec, dataset(),
                ZonedDateTime.parse("2026-11-19T01:00:00Z"), null, 1, 0));

        assertEquals(ReportConstants.STATUS_READY, data.status);
        assertEquals(1, data.rows.size());
        assertEquals(2, data.matchedRecordCount);
        assertTrue(data.hasMore);
        assertEquals("8", data.rows.get(0).get(0));
    }

    private ReportSpec spec() {
        var spec = new ReportSpec();
        spec.specVersion = 1;
        spec.source = new ReportSpec.Source();
        spec.source.datasetId = datasetId;
        spec.query = new ReportSpec.Query();
        spec.query.mode = ReportConstants.MODE_AGGREGATE;
        spec.query.timeField = ReportConstants.TIME_FIELD_RUN_STARTED_AT;
        spec.query.timeRange = new ReportSpec.TimeRange();
        spec.query.timeRange.kind = ReportConstants.RANGE_LAST_N_COMPLETE_DAYS;
        spec.query.timeRange.days = 2;
        spec.query.timezone = "Asia/Shanghai";
        spec.query.bucket = ReportConstants.BUCKET_DAY;
        spec.query.measures = List.of(
                measure("positive", "data.positive_count", ReportConstants.OP_SUM),
                measure("total", "data.total", ReportConstants.OP_SUM));
        var rate = new ReportSpec.DerivedMeasure();
        rate.id = "rate";
        rate.label = "rate";
        rate.op = ReportConstants.OP_RATIO;
        rate.numerator = "positive";
        rate.denominator = "total";
        spec.query.derivedMeasures = List.of(rate);
        spec.display = new ReportSpec.Display();
        spec.display.type = ReportConstants.DISPLAY_LINE;
        spec.display.x = "bucket";
        var series = new ReportSpec.Series();
        series.field = "rate";
        series.format = ReportConstants.FORMAT_PERCENT;
        spec.display.series = List.of(series);
        return spec;
    }

    private ReportSpec.Measure measure(String id, String field, String op) {
        var measure = new ReportSpec.Measure();
        measure.id = id;
        measure.label = id;
        measure.field = field;
        measure.op = op;
        return measure;
    }

    private Dataset dataset() {
        var dataset = new Dataset();
        dataset.id = datasetId;
        dataset.name = "report-test";
        dataset.type = DatasetType.GENERAL;
        dataset.schema = List.of(field("positive_count", SchemaFieldType.NUMBER), field("total", SchemaFieldType.NUMBER));
        return dataset;
    }

    private void insertDataset() {
        DATABASE.execute("INSERT INTO app.datasets (id, name, description, user_id, type, schema_json, schema_version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, 1, now(), now())",
                datasetId, "report-test", null, userId, "GENERAL", JsonUtil.toJson(dataset().schema));
    }

    private void insertRecord(String runStartedAt, int positive, int total) {
        DATABASE.execute("INSERT INTO dataset.dataset_records (id, dataset_id, agent_id, run_id, user_id, data, run_started_at, "
                        + "created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::timestamptz, now(), ?, now(), ?)",
                UUID.randomUUID().toString(), datasetId, "agent-1", "run-1", userId,
                "{\"positive_count\":" + positive + ",\"total\":" + total + "}", runStartedAt, "test", "test");
    }

    private SchemaField field(String name, SchemaFieldType type) {
        var field = new SchemaField();
        field.name = name;
        field.type = type;
        return field;
    }
}
