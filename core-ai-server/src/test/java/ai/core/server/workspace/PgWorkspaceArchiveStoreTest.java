package ai.core.server.workspace;

import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL integration tests for {@link PgWorkspaceArchiveStore}: archive lifecycle
 * (CREATING -> READY / FAILED -> DELETED), CAS transitions, visibility filtering, summary
 * aggregation, and retention expiry queries.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgWorkspaceArchiveStoreTest {
    private static final DatabaseImpl DATABASE = createDatabase();

    private static DatabaseImpl createDatabase() {
        var database = new DatabaseImpl("workspace-archive-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgWorkspaceArchiveRow.class);
        database.view(PgWorkspaceArchiveSummaryRow.class);
        initSchema();
        return database;
    }

    private static void initSchema() {
        var url = System.getenv("CORE_AI_PG_TEST_URL");
        var user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        var password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.workspace_archives ("
                    + "id TEXT PRIMARY KEY, "
                    + "workspace_id TEXT NOT NULL, "
                    + "volume_generation INTEGER NOT NULL, "
                    + "blob_key TEXT NOT NULL, "
                    + "sha256 TEXT NOT NULL, "
                    + "size_bytes BIGINT NOT NULL, "
                    + "file_count BIGINT, "
                    + "image TEXT, "
                    + "status TEXT NOT NULL, "
                    + "failure_reason TEXT, "
                    + "created_by TEXT, "
                    + "created_at TIMESTAMPTZ NOT NULL, "
                    + "updated_at TIMESTAMPTZ NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS workspace_archives_workspace_created_idx "
                    + "ON app.workspace_archives (workspace_id, created_at DESC)");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize workspace_archives test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private static PgWorkspaceArchiveStore store() {
        var store = new PgWorkspaceArchiveStore();
        store.database = DATABASE;
        return store;
    }

    private static WorkspaceArchiveRecord newArchive(String workspaceId, long generation, ZonedDateTime createdAt) {
        var archive = new WorkspaceArchiveRecord();
        archive.id = "arch-" + UUID.randomUUID();
        archive.workspaceId = workspaceId;
        archive.volumeGeneration = generation;
        archive.blobKey = "workspace/u1/" + workspaceId + "/" + generation + "/" + archive.id + ".tar.gz";
        archive.sha256 = "";
        archive.sizeBytes = 0L;
        archive.image = "core-ai-sandbox:latest";
        archive.status = WorkspaceArchiveStatus.CREATING;
        archive.createdBy = "u1";
        archive.createdAt = createdAt;
        archive.updatedAt = createdAt;
        return archive;
    }

    @Test
    void lifecycleCasVisibilityAndSummary() {
        var store = store();
        var workspaceId = "ws-pg-" + UUID.randomUUID();
        var now = ZonedDateTime.now();

        var a1 = newArchive(workspaceId, 0L, now.minusMinutes(2));
        var a2 = newArchive(workspaceId, 1L, now.minusMinutes(1));
        var a3 = newArchive(workspaceId, 1L, now);

        assertTrue(store.insertIfAbsent(a1));
        assertFalse(store.insertIfAbsent(a1));
        assertTrue(store.insertIfAbsent(a2));
        assertTrue(store.insertIfAbsent(a3));

        // CAS CREATING -> READY succeeds once, subsequent READY/FAILED transitions fail
        assertTrue(store.markReady(a1.id, "abc123", 4096L, 12L));
        assertFalse(store.markReady(a1.id, "other", 1L, 1L));
        assertFalse(store.markFailed(a1.id, "should not overwrite ready"));

        // CAS CREATING -> FAILED succeeds once, subsequent READY fails
        assertTrue(store.markFailed(a2.id, "blob upload timeout"));
        assertFalse(store.markReady(a2.id, "abc", 1L, 1L));

        // a3 remains CREATING; listVisible returns only READY and FAILED
        var visible = store.listVisible(workspaceId);
        assertEquals(2, visible.size());
        assertEquals(a2.id, visible.get(0).id);
        assertEquals(WorkspaceArchiveStatus.FAILED, visible.get(0).status);
        assertEquals("blob upload timeout", visible.get(0).failureReason);
        assertEquals(a1.id, visible.get(1).id);
        assertEquals(WorkspaceArchiveStatus.READY, visible.get(1).status);
        assertEquals("abc123", visible.get(1).sha256);
        assertEquals(4096L, visible.get(1).sizeBytes);
        assertEquals(12L, visible.get(1).fileCount);

        var summary = store.summarizeReady(List.of(workspaceId)).get(workspaceId);
        assertNotNull(summary);
        assertEquals(1L, summary.archiveCount());
        assertNotNull(summary.latestArchiveAt());

        // Soft delete transitions to DELETED and hides from listVisible and summarizeReady
        assertTrue(store.markDeleted(a1.id));
        assertFalse(store.markDeleted(a1.id));
        assertEquals(1, store.listVisible(workspaceId).size());
        assertFalse(store.summarizeReady(List.of(workspaceId)).containsKey(workspaceId));
    }

    @Test
    void retentionExpiryReturnsOnlyNonDeletedArchivesBeforeCutoff() {
        var store = store();
        var workspaceId = "ws-exp-" + UUID.randomUUID();
        var old = newArchive(workspaceId, 0L, ZonedDateTime.now().minusDays(40));
        var deletedOld = newArchive(workspaceId, 0L, ZonedDateTime.now().minusDays(40));
        var fresh = newArchive(workspaceId, 0L, ZonedDateTime.now().minusDays(5));

        assertTrue(store.insertIfAbsent(old));
        assertTrue(store.markReady(old.id, "oldsha", 100L, 1L));
        assertTrue(store.insertIfAbsent(deletedOld));
        assertTrue(store.markDeleted(deletedOld.id));
        assertTrue(store.insertIfAbsent(fresh));
        assertTrue(store.markReady(fresh.id, "freshsha", 200L, 2L));

        var cutoff = ZonedDateTime.now().minusDays(30);
        var expiredIds = store.listExpired(cutoff, 200).stream().map(a -> a.id).toList();
        assertTrue(expiredIds.contains(old.id));
        assertFalse(expiredIds.contains(deletedOld.id));
        assertFalse(expiredIds.contains(fresh.id));
    }
}
