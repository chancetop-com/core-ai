package ai.core.server.asynctask;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallAsyncTask;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.ToolCallResult;
import ai.core.tool.ToolExecutor;
import ai.core.tool.tools.AsyncTaskOutputTool;
import ai.core.tool.wait.WaitCapability;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCallAsyncTaskManagerSandboxTest {
    private static ToolCall sandboxTool() {
        var tool = mock(ToolCall.class);
        when(tool.getName()).thenReturn("run_bash_command");
        return tool;
    }

    private static ToolCallAsyncTask sandboxTask(ToolCall tool) {
        var pending = ToolCallResult.pending("st-1", "Async task submitted: st-1")
                .withStats("sandboxTask", "true")
                .withStats("sandboxTaskId", "task-1");
        return new ToolCallAsyncTask("st-1", tool, null, pending);
    }

    @Test
    void pollsSandboxTaskThroughTheHostPollerAndAnnouncesTerminal() {
        var store = new MapPersistence();
        var manager = new ToolCallAsyncTaskManager(store);
        var tool = sandboxTool();
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");

        var terminal = new ArrayList<ToolCallResult>();
        manager.addTerminalListener((sessionId, task, result) -> terminal.add(result));

        var polls = new int[]{0};
        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) -> {
            assertEquals("s1", sessionId);
            assertEquals("task-1", sandboxTaskId);
            return polls[0]++ == 0 ? ToolCallResult.pending("task-1", "still running")
                    : ToolCallResult.completed("OK");
        };

        assertTrue(manager.pollTask("st-1").isPending());
        var completed = manager.pollTask("st-1");
        assertTrue(completed.isCompleted());
        assertEquals("OK", completed.getResult());
        assertEquals(1, terminal.size());
        assertTrue(terminal.getFirst().isCompleted());
    }

    @Test
    void unreachableSandboxTaskBecomesLostAfterTheThreshold() {
        var store = new MapPersistence();
        var manager = new ToolCallAsyncTaskManager(store);
        var tool = sandboxTool();
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");
        manager.sandboxLostPolls = 2;

        var terminal = new ArrayList<ToolCallResult>();
        manager.addTerminalListener((sessionId, task, result) -> terminal.add(result));
        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) -> null;

        assertTrue(manager.pollTask("st-1").isPending());
        var lost = manager.pollTask("st-1");
        assertTrue(lost.isFailed());
        assertTrue(lost.getResult().contains("SANDBOX_TASK_LOST"));
        assertEquals(1, terminal.size());
        assertTrue(terminal.getFirst().isFailed());
    }

    @Test
    void notFoundTerminalizesImmediately() {
        var store = new MapPersistence();
        var manager = new ToolCallAsyncTaskManager(store);
        var tool = sandboxTool();
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");

        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) -> ToolCallResult.failed("task not found: task-1");

        var lost = manager.pollTask("st-1");
        assertTrue(lost.isFailed());
        assertTrue(lost.getResult().contains("SANDBOX_TASK_LOST"));
    }

    @Test
    void cancelsSandboxTaskThroughRelayAndAnnouncesOnce() {
        var store = new MapPersistence();
        var manager = new ToolCallAsyncTaskManager(store);
        var tool = sandboxTool();
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");

        var terminal = new ArrayList<ToolCallResult>();
        manager.addTerminalListener((sessionId, task, result) -> terminal.add(result));
        manager.sandboxTaskCanceller = (sessionId, sandboxTaskId) -> {
            assertEquals("s1", sessionId);
            assertEquals("task-1", sandboxTaskId);
            return ToolCallResult.failed("CANCELLED: task cancelled")
                    .withStats("sandboxStatus", "cancelled")
                    .withStats("taskStatus", "cancelled");
        };

        var cancelled = manager.cancelTask("st-1");
        assertTrue(cancelled.isFailed());
        assertTrue(cancelled.getResult().contains("CANCELLED"));
        assertEquals("cancelled", cancelled.getStats().get("taskStatus"));
        assertTrue(manager.isTaskTerminal("st-1"));
        assertEquals(1, terminal.size());

        // second cancel does not re-announce
        var second = manager.cancelTask("st-1");
        assertTrue(second.isFailed());
        assertTrue(second.getResult().contains("already finished"));
        assertEquals(1, terminal.size());
    }

    @Test
    void cancelWithoutRelayOrInputAreExplicitlyUnsupported() {
        var store = new MapPersistence();
        var manager = new ToolCallAsyncTaskManager(store);
        var tool = sandboxTool();
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");

        assertTrue(manager.cancelTask("st-1").getResult().contains("SANDBOX_TASK_CANCEL_UNSUPPORTED"));
        assertTrue(manager.submitInput("st-1", "x").getResult().contains("SANDBOX_TASK_INPUT_UNSUPPORTED"));
        assertFalse(manager.isTaskTerminal("st-1"));
    }

    @Test
    void waitBlocksUntilSandboxTaskCompletesAndSuppressesDuplicateNotification() throws Exception {
        var store = new MapPersistence();
        var manager = new ToolCallAsyncTaskManager(store);
        manager.waitPollIntervalMs = 50L;
        var tool = sandboxTool();
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");

        var notifications = new AtomicInteger();
        manager.addTerminalListener((sessionId, task, result) -> notifications.incrementAndGet());

        var polls = new AtomicInteger();
        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) -> polls.incrementAndGet() < 3
                ? ToolCallResult.pending("task-1", "running")
                : ToolCallResult.completed("hello-from-sandbox");

        var context = ExecutionContext.builder()
                .sessionId("s1")
                .asyncTaskManager(manager)
                .waitCapability(WaitCapability.ENABLED)
                .build();
        var executor = new ToolExecutor(List.of(), null, status -> {
        }, () -> null);
        var waitCall = FunctionCall.of("call-1", "function", AsyncTaskOutputTool.TOOL_NAME,
                "{\"action\":\"wait\",\"task_id\":\"st-1\",\"timeout_ms\":5000}");

        var waited = CompletableFuture.supplyAsync(
                () -> executor.execute(AsyncTaskOutputTool.builder().build(), waitCall, context))
                .get(5, TimeUnit.SECONDS);

        assertTrue(waited.isCompleted(), waited.getResult());
        assertEquals("hello-from-sandbox", waited.getResult());
        assertEquals("completed", waited.getStats().get("waitOutcome"));
        assertEquals(0, notifications.get(), "live wait must suppress duplicate terminal notification");

        var polled = manager.pollTask("st-1");
        assertTrue(polled.isCompleted());
        assertEquals("hello-from-sandbox", polled.getResult());
    }

    @Test
    void waitTimesOutExplicitlyAndRestoresNotification() {
        var store = new MapPersistence();
        var manager = new ToolCallAsyncTaskManager(store);
        manager.waitPollIntervalMs = 50L;
        var tool = sandboxTool();
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");

        var notifications = new AtomicInteger();
        manager.addTerminalListener((sessionId, task, result) -> notifications.incrementAndGet());
        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) -> ToolCallResult.pending("task-1", "still running");

        var context = ExecutionContext.builder()
                .sessionId("s1")
                .asyncTaskManager(manager)
                .waitCapability(WaitCapability.ENABLED)
                .build();
        var executor = new ToolExecutor(List.of(), null, status -> {
        }, () -> null);
        var waitCall = FunctionCall.of("call-2", "function", AsyncTaskOutputTool.TOOL_NAME,
                "{\"action\":\"wait\",\"task_id\":\"st-1\",\"timeout_ms\":150}");

        var timedOut = executor.execute(AsyncTaskOutputTool.builder().build(), waitCall, context);
        assertTrue(timedOut.isFailed());
        assertTrue(timedOut.getResult().startsWith("WAIT_TIMEOUT"));
        assertEquals("timeout", timedOut.getStats().get("waitOutcome"));

        // after wait times out, normal completion notification fires once
        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) -> ToolCallResult.completed("finished-later");
        assertTrue(manager.pollTask("st-1").isCompleted());
        assertEquals(1, notifications.get());
    }

    private static final class MapPersistence implements PersistenceProvider {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public void save(String id, String context) {
            values.put(id, context);
        }

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(values::remove);
        }

        @Override
        public Optional<String> load(String id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public List<String> listIds(String prefix) {
            return values.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        }
    }
}
