package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.agent.PendingUserInputQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ToolCallWaitTest {

    private static ExecutionContext contextWithWaitingMessage() {
        var context = ExecutionContext.builder().sessionId("tool-call-wait").build();
        var input = new PendingUserInputQueue(0);
        input.offer("a new message", null, null);
        context.setPendingUserInput(input);
        return context;
    }

    @Test
    void returnsTheToolResult() throws Exception {
        var outcome = ToolCallWait.await(CompletableFuture.completedFuture(ToolCallResult.completed("done")),
                5_000, "tool", ExecutionContext.empty(), new AtomicReference<>());

        assertFalse(outcome.timedOut());
        assertFalse(outcome.cancelled());
        assertEquals("done", outcome.result().getResult());
    }

    @Test
    void timesOutAndCancelsTheTool() throws Exception {
        var future = new CompletableFuture<ToolCallResult>();

        var outcome = ToolCallWait.await(future, 50, "tool", ExecutionContext.empty(), new AtomicReference<>());

        assertTrue(outcome.timedOut());
        assertFalse(outcome.cancelled());
        assertTrue(future.isCancelled());
        assertTrue(outcome.result().getResult().contains("timed out"));
    }

    @Test
    void givesUpWhenAUserMessageIsWaiting() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var toolThread = new AtomicReference<Thread>();
        var future = CompletableFuture.supplyAsync(() -> {
            toolThread.set(Thread.currentThread());
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return ToolCallResult.completed("late");
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "the tool thread must start");

        var outcome = ToolCallWait.await(future, 5_000, "tool", contextWithWaitingMessage(), toolThread);

        assertTrue(outcome.cancelled());
        assertFalse(outcome.timedOut());
        assertTrue(future.isCancelled());
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the abandoned tool thread must be interrupted");
        assertTrue(outcome.result().getResult().contains("new message"));
    }
}
