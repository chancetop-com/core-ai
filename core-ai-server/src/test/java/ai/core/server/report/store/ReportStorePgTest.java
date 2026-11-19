package ai.core.server.report.store;

import ai.core.server.dataset.store.PgCountRow;
import ai.core.server.report.domain.HomeReportPin;
import ai.core.server.report.domain.ReportDefinition;
import ai.core.server.report.domain.ReportSpec;
import ai.core.utils.JsonUtil;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author core-ai
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class ReportStorePgTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("report-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDatasetReportRow.class);
        database.view(PgHomeReportLayoutRow.class);
        database.view(PgHomeReportPinRow.class);
        database.view(PgReportLockRow.class);
        database.view(PgReportPositionRow.class);
        database.view(PgCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.dataset_reports (id text PRIMARY KEY, user_id text NOT NULL, title text NOT NULL, "
                    + "description text, revision bigint NOT NULL DEFAULT 1, spec_version integer NOT NULL, engine_version integer NOT NULL, "
                    + "spec_json jsonb NOT NULL, dataset_id text NOT NULL, dependency_json jsonb NOT NULL, origin_session_id text, "
                    + "origin_agent_id text, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE TABLE IF NOT EXISTS app.home_report_layouts (user_id text PRIMARY KEY, revision bigint NOT NULL DEFAULT 0, "
                    + "updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE TABLE IF NOT EXISTS app.home_report_pins (id text PRIMARY KEY, user_id text NOT NULL, report_id text NOT NULL, "
                    + "position integer NOT NULL, width text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), "
                    + "updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id, report_id))");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize report store test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private final String userId = "store-user-" + UUID.randomUUID();
    private PgDatasetReportStore reports;
    private PgHomeReportLayoutStore layouts;

    @BeforeEach
    void setUp() {
        reports = new PgDatasetReportStore();
        reports.database = DATABASE;
        layouts = new PgHomeReportLayoutStore();
        layouts.database = DATABASE;
    }

    @Test
    void reportInsertUpdateAndDeleteUseRevisionCas() {
        var report = report();
        reports.insert(report);

        var loaded = reports.getOwned(report.id, userId).orElseThrow();
        assertEquals(1, loaded.revision);
        assertEquals("report", loaded.title);

        loaded.title = "updated";
        loaded.updatedAt = ZonedDateTime.now();
        assertTrue(reports.update(loaded, 1));
        assertEquals(2, reports.getOwned(report.id, userId).orElseThrow().revision);
        assertTrue(reports.delete(report.id, userId, 2));
        assertTrue(reports.getOwned(report.id, userId).isEmpty());
    }

    @Test
    void layoutPinsIncrementRevisionAndUseNextPosition() {
        layouts.ensure(userId);
        var layout = layouts.lock(userId).orElseThrow();
        assertEquals(0, layout.revision);

        var first = pin(userId, reportId(), 0);
        layouts.insertPin(first);
        layouts.bumpRevision(userId, 0);
        assertEquals(1, layouts.lock(userId).orElseThrow().revision);
        assertEquals(1, layouts.countPins(userId));
        assertEquals(1, layouts.nextPosition(userId));

        var second = pin(userId, reportId(), layouts.nextPosition(userId));
        layouts.insertPin(second);
        layouts.bumpRevision(userId, 1);
        assertEquals(2, layouts.countPins(userId));
        assertTrue(layouts.deletePin(userId, first.id));
        assertEquals(1, layouts.countPins(userId));
    }

    @Test
    void advisoryLockSqlWorks() {
        try (var transaction = DATABASE.beginTransaction()) {
            var row = DATABASE.selectOne("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtext(?))) AS l",
                    PgReportLockRow.class, userId);
            assertNotNull(row.orElse(null));
            transaction.commit();
        }
        var count = DATABASE.selectOne("SELECT count(*) AS total FROM app.dataset_reports WHERE user_id = ?", PgCountRow.class, userId);
        assertEquals(0, count.orElseThrow().total);
    }

    private ReportDefinition report() {
        var report = new ReportDefinition();
        report.id = UUID.randomUUID().toString();
        report.userId = userId;
        report.title = "report";
        report.revision = 1;
        report.specVersion = 1;
        report.engineVersion = 1;
        var spec = new ReportSpec();
        spec.specVersion = 1;
        report.spec = spec;
        report.specJson = JsonUtil.toJson(spec);
        report.datasetId = "dataset-1";
        report.dependencyJson = "{}";
        report.createdAt = ZonedDateTime.now();
        report.updatedAt = report.createdAt;
        return report;
    }

    private HomeReportPin pin(String userId, String reportId, int position) {
        var pin = new HomeReportPin();
        pin.id = UUID.randomUUID().toString();
        pin.userId = userId;
        pin.reportId = reportId;
        pin.position = position;
        pin.width = "HALF";
        pin.createdAt = ZonedDateTime.now();
        pin.updatedAt = pin.createdAt;
        return pin;
    }

    private String reportId() {
        return "report-" + UUID.randomUUID();
    }
}
