package ai.core.server.sandbox;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author core-ai
 */
class SessionMcpProcessesTest {
    private final SessionMcpProcesses processes = new SessionMcpProcesses();

    @Test
    void awaitStartsReturnsImmediatelyWhenNothingIsPending() {
        long begin = System.nanoTime();
        processes.awaitStarts("session-id", 5);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMs < 500, "no pending start must not wait, took " + elapsedMs + "ms");
    }

    @Test
    void awaitStartsBlocksUntilThePendingStartSettles() throws Exception {
        processes.beginStart("session-id", "server-id");
        var finished = new CountDownLatch(1);

        var waiter = new Thread(() -> {
            processes.awaitStarts("session-id", 5);
            finished.countDown();
        });
        waiter.start();

        Thread.sleep(300);
        assertTrue(finished.getCount() > 0, "a turn must wait for the pending MCP start");

        processes.finishStart("session-id", "server-id", true);

        assertTrue(finished.await(2, TimeUnit.SECONDS), "the turn must continue once the start settles");
    }

    @Test
    void awaitStartsAlsoSettlesWhenTheStartFailed() throws Exception {
        processes.beginStart("session-id", "server-id");
        var finished = new CountDownLatch(1);
        new Thread(() -> {
            processes.awaitStarts("session-id", 5);
            finished.countDown();
        }).start();

        Thread.sleep(100);
        processes.finishStart("session-id", "server-id", false);

        assertTrue(finished.await(2, TimeUnit.SECONDS), "a failed start must not keep the turn waiting");
    }

    @Test
    void stopAllReleasesPendingStarts() throws Exception {
        processes.beginStart("session-id", "server-id");
        var finished = new CountDownLatch(1);
        new Thread(() -> {
            processes.awaitStarts("session-id", 5);
            finished.countDown();
        }).start();

        Thread.sleep(100);
        processes.stopAll("session-id", null);

        assertTrue(finished.await(2, TimeUnit.SECONDS), "releasing the sandbox must not leave a turn blocked");
    }
}
