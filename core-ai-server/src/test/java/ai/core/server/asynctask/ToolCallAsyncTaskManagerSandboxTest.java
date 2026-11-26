package ai.core.server.asynctask;

import ai.core.persistence.PersistenceProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallAsyncTask;
import ai.core.tool.ToolCallAsyncTaskManager;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        assertTrue(terminal.get(0).isCompleted());
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
        assertTrue(terminal.get(0).isFailed());
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
    void cancelAndInputAreExplicitlyUnsupported() {
        var store = new MapPersistence();
        var manager = new ToolCallAsyncTaskManager(store);
        var tool = sandboxTool();
        manager.registerTool(tool);
        manager.storeTask(sandboxTask(tool), "s1");

        assertTrue(manager.cancelTask("st-1").getResult().contains("SANDBOX_TASK_CANCEL_UNSUPPORTED"));
        assertTrue(manager.submitInput("st-1", "x").getResult().contains("SANDBOX_TASK_INPUT_UNSUPPORTED"));
        assertFalse(manager.isTaskTerminal("st-1"));
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
