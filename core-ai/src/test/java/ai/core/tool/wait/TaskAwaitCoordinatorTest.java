package ai.core.tool.wait;

import ai.core.agent.CancellationToken;
import ai.core.agent.ExecutionContext;
import ai.core.agent.PendingUserInputQueue;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.RoleType;
import ai.core.session.BackgroundTaskManager;
import ai.core.session.SessionCommandQueue;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.ToolExecutor;
import ai.core.tool.ToolOrchestration;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.tools.AsyncTaskOutputTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime completion barrier: the wait call parks the executing thread until the task's terminal
 * snapshot arrives, without an intermediate PENDING and without waking another model turn.
 *
 * @author stephen
 */
class TaskAwaitCoordinatorTest {

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ToolExecutor executor() {
        return new ToolExecutor(List.of(), null, status -> {
        }, () -> null);
    }

    private static FunctionCall waitCall(String taskId, String extra) {
        return FunctionCall.of("call_wait", "function", AsyncTaskOutputTool.TOOL_NAME,
                "{\"action\":\"wait\",\"task_id\":\"" + taskId + "\"" + extra + "}");
    }

    private static ExecutionContext context(BackgroundTaskManager manager, CancellationToken token) {
        return ExecutionContext.builder()
                .sessionId("wait-test-session")
                .taskManager(manager)
                .cancellationToken(token)
                .waitCapability(WaitCapability.ENABLED)
                .build();
    }

    @Test
    void waitParksUntilCompletionAndSuppressesNotification() throws Exception {
        var queue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(queue, InMemorySink::new, "wait-test-session", event -> {
        });
        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        manager.submit("park-1", () -> {
            started.countDown();
            awaitQuietly(gate);
            return "done";
        }, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        var context = context(manager, null);
        var tool = AsyncTaskOutputTool.builder().build();
        var future = CompletableFuture.supplyAsync(() -> executor().execute(tool, waitCall("park-1", ""), context));

        Thread.sleep(200);
        assertFalse(future.isDone(), "wait must still be parked while the task runs");
        gate.countDown();

        var result = future.get(5, TimeUnit.SECONDS);
        assertTrue(result.isCompleted());
        assertEquals("done", result.getResult());
        assertEquals("completed", result.getStats().get("waitOutcome"));
        assertTrue(result.getStats().containsKey("taskGeneration"));
        assertTrue(queue.drainSameMode().isEmpty(), "a live wait must suppress the automatic notification");
    }

    @Test
    void stopCancelsOnlyTheWaiterAndKeepsNotificationSuppressed() throws Exception {
        var queue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(queue, InMemorySink::new, "wait-test-session", event -> {
        });
        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        manager.submit("park-2", () -> {
            started.countDown();
            awaitQuietly(gate);
            return "done";
        }, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        var token = CancellationToken.create();
        var context = context(manager, token);
        var tool = AsyncTaskOutputTool.builder().build();
        var future = CompletableFuture.supplyAsync(() -> executor().execute(tool, waitCall("park-2", ""), context));

        Thread.sleep(100);
        token.cancel();

        var result = future.get(5, TimeUnit.SECONDS);
        assertTrue(result.isFailed());
        assertEquals("cancelled", result.getStats().get("waitOutcome"));
        assertTrue(manager.isRunning("park-2"), "cancelling the wait must not cancel the task");

        gate.countDown();
        assertTrue(manager.awaitTask("park-2", 5000).isCompleted());
        assertTrue(queue.drainSameMode().isEmpty(), "a late completion must not wake a cancelled wait");
    }

    @Test
    void explicitTimeoutReleasesNotificationSuppression() throws Exception {
        var queue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(queue, InMemorySink::new, "wait-test-session", event -> {
        });
        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        manager.submit("park-3", () -> {
            started.countDown();
            awaitQuietly(gate);
            return "done";
        }, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        var context = context(manager, null);
        var result = executor().execute(AsyncTaskOutputTool.builder().build(),
                waitCall("park-3", ",\"timeout_ms\":100"), context);

        assertTrue(result.isFailed());
        assertEquals("timeout", result.getStats().get("waitOutcome"));
        assertTrue(manager.isRunning("park-3"));

        gate.countDown();
        assertTrue(manager.awaitTask("park-3", 5000).isCompleted());
        assertFalse(queue.drainSameMode().isEmpty(), "an expired bounded wait must restore the notification");
    }

    @Test
    void sameTaskIdNewGenerationDoesNotWakeOldWaiter() throws Exception {
        var queue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(queue, InMemorySink::new, "wait-test-session", event -> {
        });
        var firstStarted = new CountDownLatch(1);
        var firstGate = new CountDownLatch(1);
        manager.submit("reuse-1", () -> {
            firstStarted.countDown();
            awaitQuietly(firstGate);
            return "old";
        }, null);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

        var context = context(manager, null);
        var future = CompletableFuture.supplyAsync(() -> executor().execute(
                AsyncTaskOutputTool.builder().build(), waitCall("reuse-1", ""), context));
        Thread.sleep(100);

        manager.submit("reuse-1", () -> "new", null);
        var old = future.get(5, TimeUnit.SECONDS);
        assertTrue(old.isFailed());
        assertTrue(old.getResult().contains("replaced"), old.getResult());

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ToolCallResult latest = null;
        while (System.nanoTime() < deadline) {
            latest = executor().execute(AsyncTaskOutputTool.builder().build(), waitCall("reuse-1", ""), context);
            if (latest.isCompleted()) break;
            Thread.sleep(20);
        }
        assertNotNull(latest);
        assertTrue(latest.isCompleted(), "the new generation must be waitable");
        assertEquals("new", latest.getResult());
        firstGate.countDown();
    }

    @Test
    void executorTaskKeepsTerminalSnapshotAndIsWaitable() throws Exception {
        var asyncExecutor = AsyncToolTaskExecutor.getInstance();
        var taskId = asyncExecutor.submit("wait-unit", "fake_tool", () -> "executor-done");

        ToolCallResult first = null;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            first = asyncExecutor.poll(taskId);
            if (first.isCompleted()) break;
            Thread.sleep(20);
        }
        assertNotNull(first);
        assertTrue(first.isCompleted());
        var second = asyncExecutor.poll(taskId);
        assertTrue(second.isCompleted(), "a terminal executor task must stay readable");
        assertEquals("executor-done", second.getResult());

        var context = ExecutionContext.builder().sessionId("wait-test-session")
                .waitCapability(WaitCapability.ENABLED).build();
        var waited = executor().execute(AsyncTaskOutputTool.builder().build(), waitCall(taskId, ""), context);
        assertTrue(waited.isCompleted());
        assertEquals("executor-done", waited.getResult());
    }

    @Test
    void waitIsExclusiveAndSkipsRemainingCallsWhenUserInputArrives() throws Exception {
        var queue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(queue, InMemorySink::new, "wait-test-session", event -> {
        });
        var started = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        manager.submit("exclusive-1", () -> {
            started.countDown();
            awaitQuietly(gate);
            return "done";
        }, null);
        assertTrue(started.await(5, TimeUnit.SECONDS));

        var context = context(manager, null);
        var pending = new PendingUserInputQueue(10);
        context.setPendingUserInput(pending);
        var otherRuns = new AtomicInteger();
        var other = new FakeTool("other_tool", otherRuns);
        var waitTool = AsyncTaskOutputTool.builder().build();
        var orchestration = new ToolOrchestration(
                Map.of(AsyncTaskOutputTool.TOOL_NAME, waitTool, "other_tool", other),
                List.of(), executor(), context);

        var calls = List.of(waitCall("exclusive-1", ""),
                FunctionCall.of("call_other", "function", "other_tool", "{}"));
        var future = CompletableFuture.supplyAsync(() -> orchestration.execute(calls));

        Thread.sleep(150);
        pending.offer("stop waiting, do this instead", null, null);
        gate.countDown();

        var messages = future.get(5, TimeUnit.SECONDS);
        assertEquals(0, otherRuns.get(), "a call after the wait must not start once the user steered the turn");
        assertEquals(2, messages.size(), "both tool calls must be paired");
        assertEquals(RoleType.TOOL, messages.getFirst().role);
        assertEquals("call_wait", messages.getFirst().toolCallId);
        assertEquals("call_other", messages.get(1).toolCallId);
        assertTrue(messages.get(1).getTextContent().contains("skipped"), messages.get(1).getTextContent());
    }

    private static final class FakeTool extends ToolCall {
        private final AtomicInteger runs;

        private FakeTool(String name, AtomicInteger runs) {
            setName(name);
            this.runs = runs;
            setParameters(List.of());
        }

        @Override
        public ToolCallResult execute(String arguments) {
            runs.incrementAndGet();
            return ToolCallResult.completed("other-result");
        }
    }

    private record InMemorySink(String taskId) implements SubagentOutputSink {
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
