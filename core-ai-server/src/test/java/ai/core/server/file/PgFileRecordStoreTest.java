package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgFileRecordStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("file-record-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgFileRecordRow.class);
        database.view(PgFileRecordCountRow.class);
        database.view(PgFileRecordUserRow.class);
        createTable();
        return database;
    }

    private static void createTable() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.file_records ("
                    + "id text PRIMARY KEY, user_id text NOT NULL, file_name text NOT NULL, content_type text, size bigint, "
                    + "content_hash text, storage_path text, thumb_storage_path text, share_token text, shared_at timestamptz, "
                    + "created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS file_record_test_share_token_idx "
                    + "ON app.file_records (share_token) WHERE share_token IS NOT NULL");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize file record test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgFileRecordStore store;

    @BeforeEach
    void setUp() {
        store = new PgFileRecordStore();
        store.database = DATABASE;
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE app.file_records");
        } catch (Exception e) {
            throw new IllegalStateException("failed to reset file record test table", e);
        }
    }

    @Test
    void roundTripsAndUpdatesMetadata() {
        var record = record("user-1", "report.html", "text/html", ZonedDateTime.now().minusMinutes(2));
        record.contentHash = "ABC";
        record.storagePath = "artifacts/artifacts/" + record.id + ".html";
        assertTrue(store.insertIfAbsent(record));
        assertFalse(store.insertIfAbsent(record));

        var loaded = store.get(record.id).orElseThrow();
        assertEquals("report.html", loaded.fileName);
        assertEquals("text/html", loaded.contentType);
        assertEquals("artifacts/artifacts/" + record.id + ".html", loaded.storagePath);
        assertEquals("ABC", loaded.contentHash);

        store.updateFileName(record.id, "renamed.html");
        store.updateThumbStoragePath(record.id, "artifacts/artifacts/" + record.id + ".thumb.jpg");
        store.markStored(record.id, "public/artifacts/" + record.id + ".html");
        loaded = store.get(record.id).orElseThrow();
        assertEquals("renamed.html", loaded.fileName);
        assertEquals("artifacts/artifacts/" + record.id + ".thumb.jpg", loaded.thumbStoragePath);
        assertEquals("public/artifacts/" + record.id + ".html", loaded.storagePath);
    }

    @Test
    void claimShareTokenIsAtomicAndSharedUsersAreDistinct() {
        var first = record("user-1", "a.txt", "text/plain", ZonedDateTime.now());
        var second = record("user-2", "b.txt", "text/plain", ZonedDateTime.now());
        store.insert(first);
        store.insert(second);

        assertTrue(store.claimShareToken(first.id, "user-1", "token-1", ZonedDateTime.now()));
        assertFalse(store.claimShareToken(first.id, "user-1", "token-2", ZonedDateTime.now()));
        assertEquals("token-1", store.get(first.id).orElseThrow().shareToken);

        assertTrue(store.claimShareToken(second.id, "user-2", "token-3", ZonedDateTime.now()));
        assertEquals(List.of("user-1", "user-2"), store.sharedUserIds());
    }

    @Test
    void filtersByOwnerHashIdsNamesAndShareState() {
        var oldest = record("user-1", "a.txt", "text/plain", ZonedDateTime.now().minusMinutes(5));
        oldest.contentHash = "HASH";
        var newest = record("user-1", "b.txt", "text/plain", ZonedDateTime.now());
        var foreign = record("user-2", "c.txt", "text/plain", ZonedDateTime.now());
        store.insert(oldest);
        store.insert(newest);
        store.insert(foreign);
        store.claimShareToken(newest.id, "user-1", "token-2", ZonedDateTime.now());

        var byUser = new FileRecordQuery();
        byUser.userId = "user-1";
        assertEquals(List.of(newest.id, oldest.id), store.find(byUser).stream().map(file -> file.id).toList());
        assertEquals(2, store.count(byUser));

        var byHash = new FileRecordQuery();
        byHash.userId = "user-1";
        byHash.contentHashes = List.of("HASH");
        assertEquals(oldest.id, store.find(byHash).getFirst().id);

        var byIds = new FileRecordQuery();
        byIds.ids = List.of(foreign.id);
        assertEquals(1, store.find(byIds).size());

        var byName = new FileRecordQuery();
        byName.userId = "user-1";
        byName.fileNames = List.of("b.txt");
        byName.fileNameKeyword = "b.";
        assertEquals(1, store.find(byName).size());

        var shared = new FileRecordQuery();
        shared.sharedOnly = Boolean.TRUE;
        assertEquals(1, store.find(shared).size());
        shared.shareToken = "token-2";
        assertEquals(newest.id, store.find(shared).getFirst().id);
    }

    @Test
    void rejectsInlineContent() {
        var record = record("user-1", "inline.txt", "text/plain", ZonedDateTime.now());
        record.data = "aGVsbG8=";
        assertThrows(IllegalStateException.class, () -> store.insert(record));
    }

    private FileRecord record(String userId, String fileName, String contentType, ZonedDateTime createdAt) {
        var record = new FileRecord();
        record.id = UUID.randomUUID().toString();
        record.userId = userId;
        record.fileName = fileName;
        record.contentType = contentType;
        record.size = 10L;
        record.createdAt = createdAt;
        return record;
    }
}
