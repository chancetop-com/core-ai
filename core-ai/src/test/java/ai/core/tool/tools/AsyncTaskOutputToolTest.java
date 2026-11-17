package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.session.BackgroundTaskManager;
import ai.core.session.SessionCommandQueue;
import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.wait.WaitCapability;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class AsyncTaskOutputToolTest {

    private static AsyncTaskOutputTool tool() {
        return AsyncTaskOutputTool.builder().build();
    }

    private static void sleepQuietly(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void waitReturnsResultAndSuppressesNotification() throws Exception {
        var queue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(queue, taskId -> new InMemorySink(taskId), "test-session", event -> { });
        var context = ExecutionContext.builder().sessionId("test-session").taskManager(manager).waitCapability(WaitCapability.ENABLED).build();
        manager.submit("wait-1", () -> {
            sleepQuietly(50);
            return "done";
        }, null);

        var result = tool().execute("{\"action\":\"wait\",\"task_id\":\"wait-1\",\"timeout_ms\":5000}", context);

        assertTrue(result.isCompleted());
        assertEquals("done", result.getResult());
        assertTrue(queue.drainSameMode().isEmpty());
    }

    @Test
    void waitReturnsTimeoutWithoutCancellingTheTask() throws Exception {
        var queue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(queue, taskId -> new InMemorySink(taskId), "test-session", event -> { });
        var context = ExecutionContext.builder().sessionId("test-session").taskManager(manager).waitCapability(WaitCapability.ENABLED).build();
        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        manager.submit("wait-2", () -> {
            started.countDown();
            awaitQuietly(gate);
            return "done";
        }, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        var result = tool().execute("{\"action\":\"wait\",\"task_id\":\"wait-2\",\"timeout_ms\":100}", context);

        assertTrue(result.isFailed());
        assertEquals("timeout", result.getStats().get("waitOutcome"));
        assertTrue(manager.isRunning("wait-2"), "a bounded wait must not cancel the task");
        gate.countDown();
        assertTrue(manager.awaitTask("wait-2", 5000).isCompleted());
    }

    @Test
    void waitWithoutCapabilityIsRejected() throws Exception {
        var queue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(queue, taskId -> new InMemorySink(taskId), "test-session", event -> { });
        var context = ExecutionContext.builder().sessionId("test-session").taskManager(manager).build();
        manager.submit("wait-3", () -> "done", null);

        var result = tool().execute("{\"action\":\"wait\",\"task_id\":\"wait-3\"}", context);

        assertTrue(result.isFailed());
        assertEquals("unsupported", result.getStats().get("waitOutcome"));
        assertTrue(result.getResult().startsWith("WAIT_UNSUPPORTED"));
    }

    private static final class InMemorySink implements SubagentOutputSink {
        private final String taskId;

        private InMemorySink(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public void write(String content) {
        }

        @Override
        public String getReference() {
            return "memory://" + taskId;
        }

        @Override
        public void close() {
        }
    }
}
