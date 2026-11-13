package ai.core.server.share;

import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgSessionShareStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("session-share-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgSessionShareRow.class);
        try (var connection = DriverManager.getConnection(System.getenv("CORE_AI_PG_TEST_URL"),
                database.user, database.password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.session_shares ("
                    + "id text PRIMARY KEY, session_id text NOT NULL UNIQUE, token text NOT NULL UNIQUE, "
                    + "created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, revoked_at timestamptz, "
                    + "data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize session share test schema", e);
        }
        return database;
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private PgSessionShareStore store;

    @BeforeEach
    void setUp() throws SQLException {
        store = new PgSessionShareStore();
        store.database = DATABASE;
        try (var connection = DriverManager.getConnection(System.getenv("CORE_AI_PG_TEST_URL"), DATABASE.user, DATABASE.password);
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE app.session_shares");
        }
    }

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    @Test
    void roundTripsManifestAndFindsOnlyExactToken() {
        var share = share("session-1");
        var file = new SessionShareFile();
        file.id = "manifest-file-1";
        file.fileId = "private-file-1";
        file.fileName = "result.png";
        file.size = 43L;
        share.files = List.of(file);

        assertTrue(store.replace(share, null));

        var loaded = store.getBySessionId(share.sessionId).orElseThrow();
        assertEquals(share.id, loaded.id);
        assertEquals(share.ownerUserId, loaded.ownerUserId);
        assertEquals(List.of("trace-1"), loaded.traceIds);
        assertEquals(43L, loaded.files.getFirst().size);
        assertEquals("private-file-1", loaded.files.getFirst().fileId);
        assertEquals(share.id, store.getByToken(share.token).orElseThrow().id);
        assertTrue(store.getByToken("unknown").isEmpty());
        assertTrue(store.getBySessionId("unknown").isEmpty());
    }

    @Test
    void rejectsStaleCreationReplacementAndRevocation() {
        var first = share("session-1");
        var second = share("session-1");
        assertTrue(store.replace(first, null));
        assertFalse(store.replace(second, null));
        assertFalse(store.replace(second, "wrong-generation"));
        assertTrue(store.replace(second, first.id));
        assertTrue(store.getByToken(first.token).isEmpty());

        var now = ZonedDateTime.now();
        store.revoke(second.sessionId, first.id, now);
        store.revoke("different-session", second.id, now);
        assertNull(store.getByToken(second.token).orElseThrow().revokedAt);
        store.revoke(second.sessionId, second.id, now);
        store.revoke(second.sessionId, second.id, now.plusDays(1));
        assertEquals(now.toInstant().getEpochSecond(), store.getByToken(second.token).orElseThrow().revokedAt.toEpochSecond());
    }

    @Test
    void concurrentCreatorsAndReplacementsHaveOneWinner() throws Exception {
        var first = share("session-1");
        var second = share("session-1");
        var left = executor.submit(() -> store.replace(first, null));
        var right = executor.submit(() -> store.replace(second, null));
        assertEquals(1, (left.get(10, TimeUnit.SECONDS) ? 1 : 0) + (right.get(10, TimeUnit.SECONDS) ? 1 : 0));

        var generation = store.getBySessionId("session-1").orElseThrow().id;
        var third = share("session-1");
        var fourth = share("session-1");
        var nextLeft = executor.submit(() -> store.replace(third, generation));
        var nextRight = executor.submit(() -> store.replace(fourth, generation));
        assertEquals(1, (nextLeft.get(10, TimeUnit.SECONDS) ? 1 : 0) + (nextRight.get(10, TimeUnit.SECONDS) ? 1 : 0));
    }

    private SessionShare share(String sessionId) {
        var share = new SessionShare();
        share.id = UUID.randomUUID().toString();
        share.sessionId = sessionId;
        share.ownerUserId = "owner-1";
        share.token = UUID.randomUUID().toString();
        share.title = "Shared conversation";
        share.maxMessageSeq = 2L;
        share.messageCount = 2L;
        share.createdAt = ZonedDateTime.now();
        share.expiresAt = share.createdAt.plusDays(7);
        share.traceIds = List.of("trace-1");
        share.files = List.of();
        return share;
    }
}
