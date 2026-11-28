package ai.core.server.pg;

import ai.core.server.skill.telemetry.PgSkillPapercutRow;
import ai.core.server.skill.telemetry.PgSkillRuntimeStatRow;
import ai.core.server.skill.telemetry.PgSkillTelemetryStore;
import ai.core.server.skill.telemetry.SkillPapercutCategory;
import ai.core.server.skill.telemetry.SkillPapercutInput;
import ai.core.server.skill.telemetry.SkillPapercutStatus;
import ai.core.server.skill.telemetry.SkillTelemetryDelta;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL integration tests for PgSkillTelemetryStore covering concurrent atomic increments,
 * batchId idempotency, papercut dedup state machine, and TTL cleanup.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "SACM_STATIC_ARRAY_CREATED_IN_METHOD"})
class PgSkillTelemetryStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("skill-telemetry-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.pool.size(4, 30);
        database.view(PgSkillRuntimeStatRow.class);
        database.view(PgSkillPapercutRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        var migrationPath = Path.of("..", "core-ai-server-db-migration", "src", "main", "resources",
                "db", "migration", "V63__skill_telemetry_and_papercuts.sql");
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            var sql = Files.readString(migrationPath, StandardCharsets.UTF_8);
            for (var part : sql.split(";")) {
                var trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    statement.execute(trimmed);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize skill telemetry test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgSkillTelemetryStore store() {
        var store = new PgSkillTelemetryStore();
        store.database = DATABASE;
        return store;
    }

    private void cleanupSkill(String skillId, List<String> batchIds) {
        DATABASE.execute("DELETE FROM app.skill_runtime_stats WHERE skill_id = ?", skillId);
        DATABASE.execute("DELETE FROM app.skill_papercuts WHERE skill_id = ?", skillId);
        for (var batchId : batchIds) {
            DATABASE.execute("DELETE FROM app.skill_telemetry_batches WHERE batch_id = ?", batchId);
        }
    }

    @Test
    void concurrentRecordBatchAccumulatesAtomicallyWithZeroLostUpdates() throws Exception {
        var store = store();
        var skillId = "skill-concurrent-" + UUID.randomUUID();
        var digest = "sha256-concurrent";
        int threads = 20;
        int invokesPerThread = 5;
        var batchIds = new ArrayList<String>();
        for (int i = 0; i < threads; i++) {
            batchIds.add("batch-conc-" + UUID.randomUUID());
        }
        try {
            runConcurrentBatches(store, skillId, digest, batchIds, invokesPerThread);

            var stats = store.getStats(skillId);
            assertEquals(1, stats.size());
            var row = stats.getFirst();
            assertEquals((long) threads * invokesPerThread, row.invokeCount());
            assertEquals((long) threads * 2, row.resourceReadCount());
            assertEquals((long) threads, row.materializeFailCount());
            assertEquals((long) threads * 3, row.attributedScriptCalls());
            assertEquals((long) threads, row.attributedScriptErrors());
            assertNotNull(row.lastInvokedAt());
        } finally {
            cleanupSkill(skillId, batchIds);
        }
    }

    private void runConcurrentBatches(PgSkillTelemetryStore store, String skillId, String digest,
                                      List<String> batchIds, int invokesPerThread) throws Exception {
        int threads = batchIds.size();
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (var batchId : batchIds) {
                Callable<Boolean> task = () -> {
                    ready.countDown();
                    start.await();
                    return store.recordBatch(batchId, ZonedDateTime.now().plusHours(24), List.of(
                            new SkillTelemetryDelta(skillId, digest, invokesPerThread, 2, 1, 3, 1, ZonedDateTime.now())
                    ));
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            start.countDown();
            for (var future : futures) {
                assertTrue(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replayingSameBatchIdThreeTimesIsIdempotent() {
        var store = store();
        var skillId = "skill-idem-" + UUID.randomUUID();
        var digest = "sha256-idem";
        var batchId = "batch-idem-" + UUID.randomUUID();
        var delta = new SkillTelemetryDelta(skillId, digest, 7, 3, 0, 4, 1, ZonedDateTime.now());
        try {
            assertTrue(store.recordBatch(batchId, ZonedDateTime.now().plusHours(24), List.of(delta)));
            assertFalse(store.recordBatch(batchId, ZonedDateTime.now().plusHours(24), List.of(delta)));
            assertFalse(store.recordBatch(batchId, ZonedDateTime.now().plusHours(24), List.of(delta)));

            var stats = store.getStats(skillId);
            assertEquals(1, stats.size());
            assertEquals(7L, stats.getFirst().invokeCount());
            assertEquals(3L, stats.getFirst().resourceReadCount());
            assertEquals(4L, stats.getFirst().attributedScriptCalls());
            assertEquals(1L, stats.getFirst().attributedScriptErrors());
        } finally {
            cleanupSkill(skillId, List.of(batchId));
        }
    }

    @Test
    void papercutDedupAggregatesAndSupportsLifecycleTransitions() {
        var store = store();
        var skillId = "skill-pc-" + UUID.randomUUID();
        var digest = "sha256-pc-v1";
        try {
            var first = store.upsertPapercut(new SkillPapercutInput(
                    skillId, "ns/demo", digest, SkillPapercutCategory.BROKEN_COMMAND,
                    "Script fails on Windows path!", "err-1", "use Path.of", "user-1", "sess-1"));
            var second = store.upsertPapercut(new SkillPapercutInput(
                    skillId, "ns/demo", digest, SkillPapercutCategory.BROKEN_COMMAND,
                    "  script fails on windows path ", "err-2", "use pathlib", "user-2", "sess-2"));
            var third = store.upsertPapercut(new SkillPapercutInput(
                    skillId, "ns/demo", digest, SkillPapercutCategory.BROKEN_COMMAND,
                    "Script fails on Windows path.", "err-3", null, "user-3", "sess-3"));

            assertEquals(first.id(), second.id());
            assertEquals(first.id(), third.id());
            assertEquals(3, third.occurrenceCount());
            assertEquals("err-3", third.latestObservedError());
            assertEquals("use pathlib", third.suggestedFix());
            assertEquals("sess-3", third.latestSessionId());
            assertEquals(SkillPapercutStatus.OPEN, third.status());
            assertEquals(1, store.listPapercuts(skillId, null).size());

            var ignored = store.updatePapercutStatus(skillId, first.id(), SkillPapercutStatus.IGNORED, "owner-1").orElseThrow();
            assertEquals(SkillPapercutStatus.IGNORED, ignored.status());
            assertEquals("owner-1", ignored.resolvedBy());
            assertNotNull(ignored.resolvedAt());

            var fourth = store.upsertPapercut(new SkillPapercutInput(
                    skillId, "ns/demo", digest, SkillPapercutCategory.BROKEN_COMMAND,
                    "Script fails on Windows path", "err-4", null, "user-4", "sess-4"));
            assertEquals(first.id(), fourth.id());
            assertEquals(SkillPapercutStatus.IGNORED, fourth.status());
            assertEquals(4, fourth.occurrenceCount());

            var resolved = store.updatePapercutStatus(skillId, first.id(), SkillPapercutStatus.RESOLVED, "owner-1").orElseThrow();
            assertEquals(SkillPapercutStatus.RESOLVED, resolved.status());

            var reopened = store.updatePapercutStatus(skillId, first.id(), SkillPapercutStatus.OPEN, "owner-1").orElseThrow();
            assertEquals(SkillPapercutStatus.OPEN, reopened.status());
            assertNull(reopened.resolvedBy());
            assertNull(reopened.resolvedAt());

            store.updatePapercutStatus(skillId, first.id(), SkillPapercutStatus.RESOLVED, "owner-1");
            var freshAfterResolve = store.upsertPapercut(new SkillPapercutInput(
                    skillId, "ns/demo", digest, SkillPapercutCategory.BROKEN_COMMAND,
                    "Script fails on Windows path", "err-5", null, "user-5", "sess-5"));
            assertNotEquals(first.id(), freshAfterResolve.id());
            assertEquals(1, freshAfterResolve.occurrenceCount());
            assertEquals(2, store.listPapercuts(skillId, null).size());
            assertEquals(1, store.listPapercuts(skillId, SkillPapercutStatus.OPEN).size());
        } finally {
            cleanupSkill(skillId, List.of());
        }
    }

    @Test
    void purgeExpiredBatchesDeletesOnlyExpiredRows() {
        var store = store();
        var skillId = "skill-purge-" + UUID.randomUUID();
        var expiredBatch = "batch-exp-" + UUID.randomUUID();
        var activeBatch = "batch-act-" + UUID.randomUUID();
        try {
            var now = ZonedDateTime.now();
            assertTrue(store.recordBatch(expiredBatch, now.minusMinutes(5), List.of()));
            assertTrue(store.recordBatch(activeBatch, now.plusHours(1), List.of()));

            int purged = store.purgeExpiredBatches(now);
            assertTrue(purged >= 1);
            assertTrue(store.recordBatch(expiredBatch, now.plusHours(1), List.of()));
            assertFalse(store.recordBatch(activeBatch, now.plusHours(1), List.of()));
        } finally {
            cleanupSkill(skillId, List.of(expiredBatch, activeBatch));
        }
    }
}
