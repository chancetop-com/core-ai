package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.tools.AsyncTaskOutputTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry of long-running tool calls: tasks are tagged with their session, refreshed through the
 * tool's own poll, kept after they finish, announced exactly once, and never dropped on a poll error.
 *
 * @author stephen
 */
class ToolCallAsyncTaskManagerTest {
    private static void named(ToolCall tool) {
        tool.setName("counting_tool");
        tool.setDescription("test");
    }

    private static FunctionCall call(String name) {
        var call = new FunctionCall();
        call.id = "call-1";
        call.function = new FunctionCall.Function();
        call.function.name = name;
        call.function.arguments = "{}";
        return call;
    }

    private final MapPersistence persistence = new MapPersistence();
    private final ToolCallAsyncTaskManager manager = new ToolCallAsyncTaskManager(persistence);

    @Test
    void terminalResultIsKeptAndAnnouncedOnce() {
        var tool = new CountingPollTool(2);   // pending twice, then completed
        named(tool);
        manager.registerTool(tool);
        var announced = new ArrayList<String>();
        manager.addTerminalListener((sessionId, task, result) -> announced.add(sessionId + ":" + task.taskId() + ":" + result.getStatus()));
        manager.storeTask(new ToolCallAsyncTask("t-1", tool, call(tool.getName()), ToolCallResult.pending("t-1", "started")), "session-9");

        assertEquals(List.of("t-1"), manager.listOpenTaskIds());
        assertTrue(manager.pollTask("t-1").isPending());
        assertTrue(manager.pollTask("t-1").isPending());
        assertTrue(manager.pollTask("t-1").isCompleted(), "third poll turns terminal");
        assertEquals(List.of("session-9:t-1:COMPLETED"), announced);

        // a late reader still gets the answer, no extra poll on the tool, no second announcement
        var late = manager.pollTask("t-1");
        assertTrue(late.isCompleted());
        assertEquals("done after 3", late.getResult());
        assertEquals(3, tool.polls.get());
        assertEquals(1, announced.size());
        assertTrue(manager.listOpenTaskIds().isEmpty(), "terminal tasks are no longer open");
        assertEquals(Optional.of("session-9"), manager.sessionIdOf("t-1"));

        assertEquals(0, manager.purgeTerminalOlderThan(Duration.ofHours(1)), "fresh terminal task is retained");
        assertEquals(1, manager.purgeTerminalOlderThan(Duration.ZERO));
        assertTrue(manager.pollTask("t-1").isFailed(), "purged task is gone");
    }

    @Test
    void pollErrorKeepsTheTaskAlive() {
        var tool = new CountingPollTool(Integer.MAX_VALUE) {
            @Override
            public ToolCallResult poll(String taskId) {
                throw new IllegalStateException("upstream 503");
            }
        };
        named(tool);
        manager.registerTool(tool);
        manager.storeTask(new ToolCallAsyncTask("t-2", tool, call(tool.getName()), ToolCallResult.pending("t-2", "started")), "s");

        var result = manager.pollTask("t-2");

        assertTrue(result.isFailed());
        assertEquals(List.of("t-2"), manager.listOpenTaskIds(), "the work upstream is still running; the task must survive a bad poll");
    }

    @Test
    void dropsStoredTaskWhoseToolCannotPoll() {
        // corrupt record from a pre-fix poll relay: the stored tool is the polling tool itself
        var asyncTaskOutput = AsyncTaskOutputTool.builder().build();
        manager.registerTool(asyncTaskOutput);
        manager.storeTask(new ToolCallAsyncTask("t-4", asyncTaskOutput, call(asyncTaskOutput.getName()), ToolCallResult.pending("t-4", "started")), "s");

        var result = manager.pollTask("t-4");

        assertTrue(result.isFailed());
        assertTrue(manager.loadTask("t-4").isEmpty(), "an un-pollable record must be dropped, not polled forever");
        assertTrue(manager.listOpenTaskIds().isEmpty());
    }

    @Test
    void asyncTaskOutputToolRoutesKnownTasksToTheManager() {
        var tool = new CountingPollTool(0);
        named(tool);
        manager.registerTool(tool);
        manager.storeTask(new ToolCallAsyncTask("t-3", tool, call(tool.getName()), ToolCallResult.pending("t-3", "started")), "s");
        var context = ExecutionContext.builder().sessionId("s").asyncTaskManager(manager).build();

        var result = AsyncTaskOutputTool.builder().build().execute("{\"action\":\"poll\",\"task_id\":\"t-3\"}", context);

        assertTrue(result.isCompleted(), result.getResult());
        assertEquals(1, tool.polls.get());
        var unknown = AsyncTaskOutputTool.builder().build().execute("{\"action\":\"poll\",\"task_id\":\"nope\"}", context);
        assertFalse(unknown.isCompleted());
    }

    @Test
    void pollRelayThroughExecutorDoesNotReRegisterTaskUnderPollingTool() {
        var tool = new CountingPollTool(2);   // pending twice, then completed
        named(tool);
        manager.registerTool(tool);
        var announced = new ArrayList<String>();
        manager.addTerminalListener((sessionId, task, result) -> announced.add(sessionId + ":" + task.taskId() + ":" + result.getStatus()));
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var context = ExecutionContext.builder().sessionId("s").asyncTaskManager(manager).build();
        var asyncTaskOutput = AsyncTaskOutputTool.builder().build();

        // the async tool's pending result registers the task under the tool that started it
        executor.execute(tool, call(tool.getName()), context);
        assertEquals("counting_tool", manager.loadTask("t").orElseThrow().tool().getName());

        // an agent poll through async_task_output while the task is still running returns pending
        var pollCall = FunctionCall.of("call-2", "function", asyncTaskOutput.getName(), "{\"action\":\"poll\",\"task_id\":\"t\"}");
        var poll = executor.execute(asyncTaskOutput, pollCall, context);
        assertTrue(poll.isPending(), poll.getResult());

        // ...but must not overwrite the stored record with the polling tool (which cannot poll)
        assertEquals("counting_tool", manager.loadTask("t").orElseThrow().tool().getName());
        assertTrue(manager.pollTask("t").isPending());
        assertTrue(manager.pollTask("t").isCompleted(), "the task must still complete through its real tool");
        assertEquals(List.of("s:t:COMPLETED"), announced);
    }

    /** completes on the (pendingPolls + 1)th poll */
    static class CountingPollTool extends ToolCall {
        final AtomicInteger polls = new AtomicInteger();
        private final int pendingPolls;

        CountingPollTool(int pendingPolls) {
            this.pendingPolls = pendingPolls;
        }

        @Override
        public ToolCallResult execute(String arguments) {
            return ToolCallResult.pending("t", "started");
        }

        @Override
        public ToolCallResult poll(String taskId) {
            var count = polls.incrementAndGet();
            return count <= pendingPolls ? ToolCallResult.pending(taskId, "still running") : ToolCallResult.completed("done after " + count);
        }
    }

    static class MapPersistence implements PersistenceProvider {
        private final Map<String, String> store = new TreeMap<>();

        @Override
        public void save(String id, String context) {
            store.put(id, context);
        }

        @Override
        public void clear() {
            store.clear();
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(store::remove);
        }

        @Override
        public Optional<String> load(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<String> listIds(String prefix) {
            return store.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        }
    }
}
