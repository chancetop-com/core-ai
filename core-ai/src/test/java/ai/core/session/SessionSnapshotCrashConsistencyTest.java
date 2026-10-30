package ai.core.session;

import ai.core.agent.AgentPersistence;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-consistency and multi-writer properties of the file-backed session snapshot. A snapshot that
 * was cut short by a crash or by a full disk must not take the whole session listing down with it,
 * and two writers racing on one id must never leave a file that is neither of their snapshots.
 *
 * @author stephen
 */
class SessionSnapshotCrashConsistencyTest {
    private static final String INTACT = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}],\"history\":[],\"status\":\"COMPLETED\"}";

    @Test
    void listingSurvivesATruncatedSnapshot(@TempDir Path dir) throws Exception {
        var persistence = new FileSessionPersistence(dir.toString());
        persistence.save("good", INTACT);
        Files.writeString(dir.resolve("torn.data"), INTACT.substring(0, INTACT.length() / 2));
        var manager = new SessionManager(persistence);

        assertEquals("hello", manager.firstUserMessage("good"));
        assertEquals(2, manager.listSessions().size(), "a torn file is still a session file");
        assertNull(assertDoesNotThrow(() -> manager.firstUserMessage("torn")),
                "one torn snapshot must degrade to 'no title', not throw out of the listing");
    }

    @Test
    void schemaReadIsLenientAboutUnknownFields() {
        var withFuture = "{\"messages\":[],\"history\":[],\"status\":\"COMPLETED\",\"schema_version\":2,\"future_field\":{\"x\":1}}";
        assertDoesNotThrow(() -> JSON.fromJSON(AgentPersistence.AgentPersistenceDomain.class, withFuture),
                "a snapshot written by a newer build must still load on an older one");
    }

    @Test
    void concurrentWritersNeverLeaveAMixedSnapshot(@TempDir Path dir) throws Exception {
        var a = new FileSessionPersistence(dir.toString());
        var b = new FileSessionPersistence(dir.toString());
        var payloadA = "{\"a\":\"" + "A".repeat(4_000_000) + "\"}";
        var payloadB = "{\"b\":\"" + "B".repeat(4_000_000) + "\"}";
        int mixed = 0;
        int rounds = 10;
        for (int round = 0; round < rounds; round++) {
            var start = new CountDownLatch(1);
            var t1 = Thread.ofPlatform().start(() -> { await(start); a.save("shared", payloadA); });
            var t2 = Thread.ofPlatform().start(() -> { await(start); b.save("shared", payloadB); });
            start.countDown();
            t1.join(30_000);
            t2.join(30_000);
            var content = Files.readString(dir.resolve("shared.data"));
            if (!content.equals(payloadA) && !content.equals(payloadB)) mixed++;
        }
        assertEquals(0, mixed, "rounds that ended with a file that is neither writer's snapshot: " + mixed + "/" + rounds);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
