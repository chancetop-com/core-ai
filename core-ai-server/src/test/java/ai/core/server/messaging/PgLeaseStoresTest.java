package ai.core.server.messaging;

import ai.core.server.session.PgSessionBudgetStore;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgLeaseStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("lease-stores-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgLeaseOwnerRow.class);
        database.view(PgLeaseCountRow.class);
        database.view(PgPodAddressRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS messaging");
            statement.execute("CREATE TABLE IF NOT EXISTS messaging.pods ("
                    + "pod_id text PRIMARY KEY, address text NOT NULL, started_at timestamptz NOT NULL, "
                    + "heartbeat_at timestamptz NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS messaging.session_ownership ("
                    + "resource_id text PRIMARY KEY, owner_pod text NOT NULL, acquired_at timestamptz NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS messaging.turn_states ("
                    + "session_id text PRIMARY KEY, owner_pod text NOT NULL, user_id text, started_at timestamptz NOT NULL)");
            statement.execute("DROP TABLE IF EXISTS messaging.user_session_counts");
            statement.execute("CREATE TABLE IF NOT EXISTS messaging.user_session_counts ("
                    + "user_id text PRIMARY KEY, count integer NOT NULL, expires_at timestamptz NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize lease store test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private String id;
    private String pod1;
    private String pod2;

    @BeforeEach
    void setUp() {
        id = "test-" + UUID.randomUUID();
        pod1 = "pod-a-" + UUID.randomUUID();
        pod2 = "pod-b-" + UUID.randomUUID();
        registerPod(pod1);
        registerPod(pod2);
    }

    @Test
    void ownershipFollowsPodHeartbeat() {
        var store = new PgOwnershipStore();
        store.database = DATABASE;

        assertTrue(store.claim(id, pod1));
        assertFalse(store.claim(id, pod2));
        assertEquals(pod1, store.owner(id));
        assertTrue(store.renew(id, pod1));
        assertFalse(store.renew(id, pod2));

        heartbeatAgeSeconds(pod1, 60);
        assertNull(store.owner(id));
        assertTrue(store.claim(id, pod2));
        assertEquals(pod2, store.owner(id));

        store.release(id, pod2);
        assertNull(store.owner(id));
    }

    @Test
    void turnStateFollowsPodHeartbeat() {
        var store = new PgTurnStateStore();
        store.database = DATABASE;
        var secondSession = "test-" + UUID.randomUUID();

        store.markRunning(id, "user-" + id, pod1);
        store.markRunning(secondSession, "user-" + id, pod2);
        assertEquals(TurnStateRegistry.TurnLiveness.RUNNING, store.liveness(id));
        assertEquals(2, store.inFlightTurns("user-" + id));

        heartbeatAgeSeconds(pod1, 60);
        assertEquals(TurnStateRegistry.TurnLiveness.NOT_RUNNING, store.liveness(id));
        assertEquals(1, store.inFlightTurns("user-" + id));

        store.clear(secondSession, "user-" + id, pod2);
        assertEquals(0, store.inFlightTurns("user-" + id));
    }

    @Test
    void sessionBudgetCountsPerUserAndExpires() {
        var store = new PgSessionBudgetStore();
        store.database = DATABASE;
        var user = "user-" + id;

        assertTrue(store.acquire(user, 1));
        assertFalse(store.acquire(user, 1));

        store.release(user);
        assertTrue(store.acquire(user, 1));

        DATABASE.execute("UPDATE messaging.user_session_counts SET expires_at = now() - make_interval(secs => ?) WHERE user_id = ?",
            1, user);
        assertTrue(store.acquire(user, 1));
    }

    @Test
    void podAddressExpiresWithHeartbeat() {
        var registry = new PodRegistry(pod1, "unused");
        registry.database = DATABASE;
        DATABASE.execute("UPDATE messaging.pods SET address = ? WHERE pod_id = ?", "10.0.0.1:8080", pod1);

        assertEquals("10.0.0.1:8080", registry.address(pod1));

        heartbeatAgeSeconds(pod1, 60);
        assertNull(registry.address(pod1));
    }

    private void registerPod(String podId) {
        DATABASE.execute("INSERT INTO messaging.pods (pod_id, address, started_at, heartbeat_at) "
            + "VALUES (?, ?, now(), now()) ON CONFLICT (pod_id) DO UPDATE SET heartbeat_at = now()", podId, podId + ":8080");
    }

    private void heartbeatAgeSeconds(String podId, int seconds) {
        DATABASE.execute("UPDATE messaging.pods SET heartbeat_at = now() - make_interval(secs => ?) WHERE pod_id = ?",
            seconds, podId);
    }
}
