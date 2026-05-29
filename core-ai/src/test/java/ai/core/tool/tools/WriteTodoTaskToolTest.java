package ai.core.tool.tools;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.tool.function.Functions;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author lim chen
 */
class WriteTodoTaskToolTest {

    private InMemoryTodoStore store;
    private WriteTodoTaskTool tool;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        store = new InMemoryTodoStore();
        tool = new WriteTodoTaskTool();
        context = ExecutionContext.builder()
                .sessionId("test-session")
                .todoStoreFactory(_ -> store)
                .build();
    }

    @Test
    void testWrap() {
        var wtl = spy(new WriteTodoTaskTool());
        var tools = Functions.from(wtl);
        assertFalse(tools.isEmpty());

    }

    @Test
    void testMockCreateFc() {
        var llmProvider = Mockito.mock(LiteLLMProvider.class);
        var wtl = spy(new WriteTodoTaskTool());
        var agent = Agent.builder()
                .systemPrompt("You are a helpful assistant")
                .llmProvider(llmProvider)
                .toolCalls(Functions.from(wtl))
                .maxTurn(2)
                .build();
        agent.setExecutionContext(ExecutionContext.builder()
                .sessionId("test-session")
                .todoStoreFactory(_ -> store)
                .build());
        String toolCallResponse = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"I will create a task to fix the file length issue.","tool_calls":[{"id":"call_00_LcHBWhjiCMktHjwzn6Oc0436","type":"function","function":{"name":"task_create","arguments":"{\\"subject\\": \\"Fix AgentBuilder.java file length\\", \\"description\\": \\"Reduce AgentBuilder.java to max 450 lines\\", \\"activeForm\\": \\"Fixing AgentBuilder.java file length\\"}"},"index":null}]},"delta":null,"index":null}],"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}
                """;
        String finishResponse = """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"I've created a task to track this work.","tool_calls":[]},"delta":null,"index":null}],"usage":{"prompt_tokens":200,"completion_tokens":30,"total_tokens":230}}
                """;
        var crs1 = JsonUtil.fromJson(CompletionResponse.class, toolCallResponse);
        var crs2 = JsonUtil.fromJson(CompletionResponse.class, finishResponse);

        when(llmProvider.completionStream(any(), any(), any())).thenReturn(crs1, crs2);
        try {
            agent.run("Fix the file length checkstyle issue");
        } catch (Exception ignored) {
            // max turns exceeded is expected in unit test
        }
        verify(wtl).createTask(any(), any());

    }

    @Test
    void createTaskShouldReturnTaskWithAssignedId() {
        var params = new WriteTodoTaskTool.CreateTaskParams();
        params.subject = "Fix authentication bug";
        params.description = "Investigate and fix the login flow issue";

        WriteTodoTaskTool.TaskEntity result = tool.createTask(params, context);

        assertEquals("1", result.id);
        assertEquals("Fix authentication bug", result.subject);
        assertEquals("pending", result.status);

        WriteTodoTaskTool.TaskEntity saved = store.read("1");
        assertNotNull(saved);
        assertEquals("1", saved.id);
    }

    @Test
    void createTaskWithActiveFormAndMetadata() {
        var params = new WriteTodoTaskTool.CreateTaskParams();
        params.subject = "Run tests";
        params.description = "Execute the full test suite";
        params.activeForm = "Running tests";
        params.metadata = Map.of("priority", "high");

        tool.createTask(params, context);

        WriteTodoTaskTool.TaskEntity saved = store.read("1");
        assertEquals("Run tests", saved.subject);
        assertEquals("Running tests", saved.activeForm);
        assertEquals("high", saved.metadata.get("priority"));
    }

    @Test
    void multipleCreateTasksShouldAssignSequentialIds() {
        tool.createTask(makeCreateParams("Task A", "First"), context);
        tool.createTask(makeCreateParams("Task B", "Second"), context);
        tool.createTask(makeCreateParams("Task C", "Third"), context);

        assertEquals("1", store.read("1").id);
        assertEquals("2", store.read("2").id);
        assertEquals("3", store.read("3").id);
    }

    @Test
    void updateTaskShouldChangeStatusToInProgress() {
        store.setup(makeTask("1", "pending", "Test task"));

        String result = tool.updateTask(makeUpdateParams("1", "in_progress"), context);

        assertTrue(result.contains("Status: in_progress"));
        assertEquals("in_progress", store.read("1").status);
    }

    @Test
    void updateTaskShouldChangeStatusToCompleted() {
        store.setup(makeTask("1", "in_progress", "Test task"));

        String result = tool.updateTask(makeUpdateParams("1", "completed"), context);

        assertTrue(result.contains("Status: completed"));
        assertTrue(result.contains("Task #1 completed"));
        assertTrue(result.contains("task_list to find your next"));
        assertEquals("completed", store.read("1").status);
    }

    @Test
    void updateTaskShouldDeleteTask() {
        store.setup(makeTask("1", "pending", "Test task"));

        String result = tool.updateTask(withStatus("1", "deleted"), context);

        assertTrue(result.contains("Task #1 has been deleted"));
        assertNull(store.read("1"));
    }

    @Test
    void updateTaskShouldReturnErrorForNonExistentTask() {
        String result = tool.updateTask(makeUpdateParams("99", "in_progress"), context);

        assertTrue(result.contains("\"error\""));
        assertTrue(result.contains("not found"));
    }

    @Test
    void updateTaskShouldSetDependenciesViaAddBlocks() {
        store.setup(makeTask("1", "pending", "First task"));
        store.setup(makeTask("2", "pending", "Second task"));

        tool.updateTask(withBlocks("1", List.of("2")), context);

        assertTrue(store.read("1").blocks.contains("2"));
        assertTrue(store.read("2").blockedBy.contains("1"));
    }

    @Test
    void updateTaskShouldSetDependenciesViaAddBlockedBy() {
        store.setup(makeTask("1", "pending", "First task"));
        store.setup(makeTask("2", "pending", "Second task"));

        tool.updateTask(withBlockedBy("2", List.of("1")), context);

        assertTrue(store.read("1").blocks.contains("2"));
        assertTrue(store.read("2").blockedBy.contains("1"));
    }

    @Test
    void updateTaskShouldMergeMetadata() {
        store.setup(makeTask("1", "pending", "Task with metadata"));
        store.read("1").metadata = new LinkedHashMap<>(Map.of("key1", "value1", "key2", "value2"));

        var params = makeUpdateParams("1", null);
        params.metadata = Map.of("key2", "updated", "key3", "value3");
        tool.updateTask(params, context);

        Map<String, Object> meta = store.read("1").metadata;
        assertEquals("value1", meta.get("key1"));
        assertEquals("updated", meta.get("key2"));
        assertEquals("value3", meta.get("key3"));
    }

    @Test
    void updateTaskShouldDeleteMetadataKeysWithNullValue() {
        store.setup(makeTask("1", "pending", "Task"));
        store.read("1").metadata = new LinkedHashMap<>(Map.of("key1", "value1", "key2", "value2"));

        var params = makeUpdateParams("1", null);
        params.metadata = new LinkedHashMap<>();
        params.metadata.put("key1", null);
        tool.updateTask(params, context);

        Map<String, Object> meta = store.read("1").metadata;
        assertTrue(meta.containsKey("key2"));
        assertNull(meta.get("key1"));
    }

    @Test
    void updateTaskShouldSkipSelfReferencingDependencies() {
        store.setup(makeTask("1", "pending", "Self task"));

        tool.updateTask(withBlocks("1", List.of("1")), context);
        tool.updateTask(withBlockedBy("1", List.of("1")), context);

        assertTrue(store.read("1").blocks.isEmpty());
        assertTrue(store.read("1").blockedBy.isEmpty());
    }

    @Test
    void listTasksShouldReturnAllTasks() {
        store.setup(makeTask("1", "pending", "First"));
        store.setup(makeTask("2", "in_progress", "Second"));
        store.setup(makeTask("3", "completed", "Third"));

        String result = tool.listTasks(context);

        assertTrue(result.contains("\"id\":\"1\""));
        assertTrue(result.contains("\"id\":\"2\""));
        assertTrue(result.contains("\"id\":\"3\""));
    }

    @Test
    void listTasksShouldFilterCompletedBlockers() {
        store.setup(makeTask("1", "completed", "Done blocker"));
        store.setup(makeTask("2", "pending", "Blocked task"));
        store.read("2").blockedBy = new ArrayList<>(List.of("1"));

        String result = tool.listTasks(context);

        assertTrue(result.contains("\"id\":\"2\""));
        // blockedBy should be empty since #1 is completed
        assertTrue(result.contains("\"status\":\"pending\"}"));
    }

    @Test
    void listTasksShouldFilterInternalTasks() {
        var internal = makeTask("1", "pending", "Internal task");
        internal.metadata = Map.of("_internal", true);
        store.setup(internal);
        store.setup(makeTask("2", "pending", "Visible task"));

        String result = tool.listTasks(context);

        assertTrue(result.contains("\"id\":\"2\""));
        assertTrue(result.contains("Visible task"));
        assertTrue(!result.contains("\"id\":\"1\"") || !result.contains("Internal task"));
    }

    @Test
    void listTasksShouldReturnEmptyMessageForNoTasks() {
        String result = tool.listTasks(context);

        assertTrue(result.contains("\"tasks\": []"));
        assertTrue(result.contains("No tasks found"));
    }

    @Test
    void getTaskShouldReturnFullDetails() {
        store.setup(makeTask("1", "in_progress", "Test task"));

        WriteTodoTaskTool.TaskEntity result = tool.getTask(makeGetParams("1"), context);

        assertNotNull(result);
        assertEquals("1", result.id);
        assertEquals("Test task", result.subject);
        assertEquals("in_progress", result.status);
    }

    @Test
    void getTaskShouldReturnNullForNonExistentTask() {
        WriteTodoTaskTool.TaskEntity result = tool.getTask(makeGetParams("99"), context);

        assertNull(result);
    }

    @Test
    void fullCrudLifecycle() {
        // Create tasks
        tool.createTask(makeCreateParams("Plan feature", "Design the architecture"), context);
        tool.createTask(makeCreateParams("Implement feature", "Write the code"), context);
        tool.createTask(makeCreateParams("Test feature", "Run tests and verify"), context);

        // List all
        String listResult = tool.listTasks(context);
        assertTrue(listResult.contains("Plan feature"));
        assertTrue(listResult.contains("Implement feature"));
        assertTrue(listResult.contains("Test feature"));

        // Start first task
        tool.updateTask(makeUpdateParams("1", "in_progress"), context);
        assertEquals("in_progress", store.read("1").status);

        // Set dependency: #2 blocked by #1
        tool.updateTask(withBlockedBy("2", List.of("1")), context);
        assertTrue(store.read("2").blockedBy.contains("1"));

        // Complete #1
        String completeResult = tool.updateTask(makeUpdateParams("1", "completed"), context);
        assertTrue(completeResult.contains("Task #1 completed"));
        assertEquals("completed", store.read("1").status);

        // Verification nudge when all 3 completed
        tool.updateTask(makeUpdateParams("2", "completed"), context);
        String finalResult = tool.updateTask(makeUpdateParams("3", "completed"), context);
        assertTrue(finalResult.contains("verification step"));

        // Delete a task
        tool.updateTask(withStatus("1", "deleted"), context);
        assertNull(store.read("1"));
    }

    // ---- helpers ----

    private WriteTodoTaskTool.CreateTaskParams makeCreateParams(String subject, String description) {
        var params = new WriteTodoTaskTool.CreateTaskParams();
        params.subject = subject;
        params.description = description;
        return params;
    }

    private WriteTodoTaskTool.UpdateTaskParams makeUpdateParams(String taskId, String status) {
        var params = new WriteTodoTaskTool.UpdateTaskParams();
        params.taskId = taskId;
        params.status = status;
        return params;
    }

    private WriteTodoTaskTool.UpdateTaskParams withBlocks(String taskId, List<String> addBlocks) {
        var params = new WriteTodoTaskTool.UpdateTaskParams();
        params.taskId = taskId;
        params.addBlocks = addBlocks;
        return params;
    }

    private WriteTodoTaskTool.UpdateTaskParams withBlockedBy(String taskId, List<String> addBlockedBy) {
        var params = new WriteTodoTaskTool.UpdateTaskParams();
        params.taskId = taskId;
        params.addBlockedBy = addBlockedBy;
        return params;
    }

    private WriteTodoTaskTool.UpdateTaskParams withStatus(String taskId, String status) {
        var params = new WriteTodoTaskTool.UpdateTaskParams();
        params.taskId = taskId;
        params.status = status;
        return params;
    }

    private WriteTodoTaskTool.GetTaskParams makeGetParams(String taskId) {
        var params = new WriteTodoTaskTool.GetTaskParams();
        params.taskId = taskId;
        return params;
    }

    private WriteTodoTaskTool.TaskEntity makeTask(String id, String status, String subject) {
        var task = new WriteTodoTaskTool.TaskEntity();
        task.id = id;
        task.status = status;
        task.subject = subject;
        task.description = "Description for " + subject;
        task.blocks = new ArrayList<>();
        task.blockedBy = new ArrayList<>();
        return task;
    }

    /**
     * In-memory TodoStore for testing — avoids filesystem dependency.
     */
    private static final class InMemoryTodoStore implements TodoStore {
        private final Map<String, WriteTodoTaskTool.TaskEntity> tasks = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger(1);

        void setup(WriteTodoTaskTool.TaskEntity task) {
            tasks.put(task.id, task);
            int idNum = Integer.parseInt(task.id);
            nextId.updateAndGet(v -> Math.max(v, idNum + 1));
        }

        @Override
        public WriteTodoTaskTool.TaskEntity create(WriteTodoTaskTool.TaskEntity task) {
            task.id = String.valueOf(nextId.getAndIncrement());
            tasks.put(task.id, task);
            return task;
        }

        @Override
        public WriteTodoTaskTool.TaskEntity read(String taskId) {
            return tasks.get(taskId);
        }

        @Override
        public void write(String taskId, WriteTodoTaskTool.TaskEntity task) {
            tasks.put(taskId, task);
        }

        @Override
        public void delete(String taskId) {
            tasks.remove(taskId);
            for (var t : tasks.values()) {
                t.blocks.remove(taskId);
                t.blockedBy.remove(taskId);
            }
        }

        @Override
        public List<WriteTodoTaskTool.TaskEntity> listAll() {
            var result = new ArrayList<>(tasks.values());
            result.sort(Comparator.comparingInt(a -> Integer.parseInt(a.id)));
            return result;
        }


        @Override
        public void cleanup() {
            tasks.clear();
            nextId.set(1);
        }
    }
}
