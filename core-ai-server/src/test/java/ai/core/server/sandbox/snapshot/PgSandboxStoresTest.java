package ai.core.server.sandbox.snapshot;

import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgSandboxStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("sandbox-snapshot-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgSandboxSnapshotRow.class);
        database.view(PgSandboxEpochRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.sandbox_snapshots ("
                + "id text PRIMARY KEY, session_id text NOT NULL, user_id text NOT NULL, epoch bigint NOT NULL, "
                + "status text NOT NULL, blob_key text NOT NULL, sha256 text, size_bytes bigint, file_count integer, "
                + "image text, runtime_version text, created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, "
                + "data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.sandbox_epochs ("
                + "id text PRIMARY KEY, epoch bigint NOT NULL, updated_at timestamptz NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize sandbox snapshot test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTables() {
        DATABASE.execute("DELETE FROM app.sandbox_snapshots");
        DATABASE.execute("DELETE FROM app.sandbox_epochs");
    }

    @Test
    void epochInsertAndCompareAndIncrement() {
        var store = new PgSandboxEpochStore();
        store.database = DATABASE;
        var doc = new SandboxEpochDoc();
        doc.id = "session-1";
        doc.epoch = 1L;
        doc.updatedAt = ZonedDateTime.now();

        assertTrue(store.insertIfAbsent(doc));
        assertFalse(store.insertIfAbsent(doc));
        assertEquals(1L, store.get("session-1").orElseThrow().epoch);
        assertTrue(store.compareAndIncrement("session-1", 1L, 2L));
        assertFalse(store.compareAndIncrement("session-1", 1L, 2L));
        assertEquals(2L, store.get("session-1").orElseThrow().epoch);
    }

    @Test
    void snapshotLifecycle() {
        var store = new PgSandboxSnapshotStore();
        store.database = DATABASE;
        var doc = snapshot("UPLOADING");
        store.insert(doc);

        assertTrue(store.markAvailable(doc.id));
        assertFalse(store.markAvailable(doc.id));
        assertEquals(doc.id, store.findLatestAvailable(doc.sessionId).id);
        assertEquals(1, store.listBySession(doc.sessionId).size());
        assertEquals(1, store.listExpired(ZonedDateTime.now().plusDays(10), 10).size());

        store.tombstone(doc.id);
        var tombstoned = store.listBySession(doc.sessionId).getFirst();
        assertEquals(SandboxSnapshotDoc.STATUS_DELETED, tombstoned.status);
        store.delete(doc.id);
        assertTrue(store.listBySession(doc.sessionId).isEmpty());
    }

    private SandboxSnapshotDoc snapshot(String status) {
        var doc = new SandboxSnapshotDoc();
        doc.id = UUID.randomUUID().toString();
        doc.sessionId = "session-1";
        doc.userId = "user-1";
        doc.epoch = 1L;
        doc.status = status;
        doc.blobKey = "user-1/session-1/" + doc.id + ".tar.gz";
        doc.createdAt = ZonedDateTime.now();
        doc.expiresAt = doc.createdAt.plusDays(1);
        return doc;
    }
}
