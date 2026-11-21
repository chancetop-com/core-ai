package ai.core.server.messaging;

import ai.core.api.server.session.ApprovalDecision;
import core.framework.internal.db.DatabaseImpl;
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

@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgCommandQueueTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("command-queue-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgCommandRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS messaging.pods (pod_id text PRIMARY KEY, address text NOT NULL, "
                    + "command_backend text NOT NULL DEFAULT 'redis', started_at timestamptz NOT NULL, heartbeat_at timestamptz NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS messaging.commands ("
                    + "id bigserial PRIMARY KEY, type text NOT NULL, session_id text NOT NULL, user_id text, payload text NOT NULL, "
                    + "request_id text, target_pod text, status text NOT NULL DEFAULT 'pending', claimed_by text, claimed_at timestamptz, "
                    + "attempts integer NOT NULL DEFAULT 0, available_at timestamptz NOT NULL DEFAULT now(), "
                    + "created_at timestamptz NOT NULL DEFAULT now(), last_error text)");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to initialize command queue test schema", e);
        }
    }

    private PgCommandQueue queue;
    private String podA;
    private String podB;

    @BeforeEach
    void setUp() {
        queue = new PgCommandQueue();
        queue.database = DATABASE;
        DATABASE.execute("DELETE FROM messaging.commands");
        podA = "pod-a-" + UUID.randomUUID();
        podB = "pod-b-" + UUID.randomUUID();
    }

    @Test
    void claimOnlyReturnsCommandsForThisPodAndCompletes() {
        queue.publish(command("s-1"), podA);
        queue.publish(command("s-2"), podB);
        queue.publish(command("s-3"), null);

        var claimed = queue.claim(podA, 10);
        assertEquals(2, claimed.size());
        assertEquals("s-1", claimed.get(0).command().sessionId());
        assertEquals("s-3", claimed.get(1).command().sessionId());

        queue.complete(claimed.get(0).id(), podA);
        queue.complete(claimed.get(1).id(), podA);
        assertTrue(queue.claim(podA, 10).isEmpty());
    }

    @Test
    void failedCommandIsRetriedThenMarkedFailed() {
        queue.publish(command("s-1"), podA);
        var first = queue.claim(podA, 10).get(0);
        queue.fail(first.id(), podA, first.attempts(), new IllegalStateException("boom"));
        assertTrue(queue.claim(podA, 10).isEmpty());

        sleep(1100);
        var second = queue.claim(podA, 10).get(0);
        assertEquals(2, second.attempts());
        queue.fail(second.id(), podA, second.attempts(), new IllegalStateException("boom"));
        sleep(5100);
        var third = queue.claim(podA, 10).get(0);
        assertEquals(3, third.attempts());
        queue.fail(third.id(), podA, third.attempts(), new IllegalStateException("boom"));
        sleep(200);
        assertTrue(queue.claim(podA, 10).isEmpty());
    }

    @Test
    void releasedClaimIsAvailableAgain() {
        queue.publish(command("s-1"), podA);
        assertFalse(queue.claim(podA, 10).isEmpty());
        queue.releaseClaimedBy(podA);
        assertEquals(1, queue.claim(podA, 10).size());
    }

    @Test
    void retryLaterClearsTheTargetAndResetsAttempts() {
        queue.publish(command("s-1"), podA);
        var claimed = queue.claim(podA, 10).get(0);

        queue.retryLater(claimed.id(), podA, 0);
        sleep(100);

        var row = DATABASE.selectOne("SELECT id, type, session_id, user_id, payload, request_id, target_pod, attempts "
                + "FROM messaging.commands WHERE id = ?", PgCommandRow.class, claimed.id()).orElseThrow();
        assertNull(row.targetPod);
        assertEquals(0, row.attempts.intValue());
        assertEquals(1, queue.claim(podB, 10).size(), "another pod must be able to pick up a requeued command");
    }

    @Test
    void cleanupClearsTargetsOfDeadPodsAndKeepsLiveTargets() {
        var deadPod = "dead-" + UUID.randomUUID();
        var livePod = "live-" + UUID.randomUUID();
        insertPod(deadPod, 300);
        insertPod(livePod, 0);
        queue.publish(command("s-dead"), deadPod);
        queue.publish(command("s-live"), livePod);

        queue.cleanup();

        assertNull(targetOf("s-dead"));
        assertEquals(livePod, targetOf("s-live"));
        var claimed = queue.claim(podB, 10);
        assertEquals(1, claimed.size());
        assertEquals("s-dead", claimed.get(0).command().sessionId());
    }

    private void insertPod(String podId, long heartbeatAgeSeconds) {
        DATABASE.execute("INSERT INTO messaging.pods (pod_id, address, started_at, heartbeat_at) "
                        + "VALUES (?, ?, now(), now() - make_interval(secs => ?))",
                podId, "127.0.0.1:8080", heartbeatAgeSeconds);
    }

    private String targetOf(String sessionId) {
        return DATABASE.selectOne("SELECT id, type, session_id, user_id, payload, request_id, target_pod, attempts "
                + "FROM messaging.commands WHERE session_id = ?", PgCommandRow.class, sessionId)
                .map(row -> row.targetPod).orElse(null);
    }

    private SessionCommand command(String sessionId) {
        return SessionCommand.approveToolCall(sessionId, "user", "call", ApprovalDecision.APPROVE);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
