package ai.core.server.asynctask;

import ai.core.agent.ExecutionContext;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallAsyncTask;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.AsyncTaskOutputTool;
import ai.core.tool.wait.WaitCapability;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncTaskOutputToolManagedWaitTest {
    private static ToolCallAsyncTask sandboxTask(ToolCall tool) {
        var pending = ToolCallResult.pending("st-1", "Async task submitted: st-1")
                .withStats("sandboxTask", "true")
                .withStats("sandboxTaskId", "task-1");
        return new ToolCallAsyncTask("st-1", tool, null, pending);
    }

    @Test
    void waitBlocksUntilTheSandboxTaskIsTerminal() {
        var manager = new ToolCallAsyncTaskManager(new MapPersistence());
        var tool = mock(ToolCall.class);
        when(tool.getName()).thenReturn("run_bash_command");
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");
        var polls = new int[]{0};
        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) ->
                polls[0]++ == 0 ? ToolCallResult.pending("task-1", "still running") : ToolCallResult.completed("OK");
        var context = ExecutionContext.builder().sessionId("s1").asyncTaskManager(manager)
                .waitCapability(WaitCapability.ENABLED).build();

        var result = AsyncTaskOutputTool.builder().build()
                .execute("{\"action\":\"wait\",\"task_id\":\"st-1\",\"timeout_ms\":3000}", context);

        assertTrue(result.isCompleted());
        assertEquals("OK", result.getResult());
    }

    @Test
    void waitRoutesByTaskRecordEvenWhenTheToolCannotBeResolved() {
        var manager = new ToolCallAsyncTaskManager(new MapPersistence());
        var tool = mock(ToolCall.class);
        when(tool.getName()).thenReturn("run_bash_command");
        // deliberately not registered: loadTask() would be empty, the task record is what routing needs
        manager.storeTask(sandboxTask(tool), "s1");
        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) -> ToolCallResult.completed("OK");
        var context = ExecutionContext.builder().sessionId("s1").asyncTaskManager(manager)
                .waitCapability(WaitCapability.ENABLED).build();

        var result = AsyncTaskOutputTool.builder().build()
                .execute("{\"action\":\"wait\",\"task_id\":\"st-1\",\"timeout_ms\":3000}", context);

        assertTrue(result.isCompleted());
        assertEquals("OK", result.getResult());
    }

    @Test
    void waitTimesOutWithoutKillingTheTask() {
        var manager = new ToolCallAsyncTaskManager(new MapPersistence());
        var tool = mock(ToolCall.class);
        when(tool.getName()).thenReturn("run_bash_command");
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");
        manager.sandboxTaskPoller = (sessionId, sandboxTaskId) -> ToolCallResult.pending("task-1", "still running");
        var context = ExecutionContext.builder().sessionId("s1").asyncTaskManager(manager)
                .waitCapability(WaitCapability.ENABLED).build();

        var result = AsyncTaskOutputTool.builder().build()
                .execute("{\"action\":\"wait\",\"task_id\":\"st-1\",\"timeout_ms\":100}", context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("WAIT_TIMEOUT"));
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
