package ai.core.agent.lifecycle;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.persistence.PersistenceProvider;
import ai.core.prompt.SystemVariables;
import ai.core.tool.function.Functions;
import ai.core.tool.tools.TodoLifecycle;
import ai.core.tool.tools.WriteTodosTool;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoLifecycleTest {

    static class InMemoryPersistenceProvider implements PersistenceProvider {
        private final Map<String, String> store = new HashMap<>();

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
    }

    @Test
    void todoLifecycleLoadsTodosOnBeforeAgentRun() {
        var persistence = new InMemoryPersistenceProvider();
        var sessionId = "test-session";

        // Pre-persist todos
        var todosJson = "[{\"content\":\"task one\",\"status\":\"PENDING\"},{\"content\":\"task two\",\"status\":\"COMPLETED\"}]";
        persistence.save("todos:" + sessionId, todosJson);

        var context = ExecutionContext.builder()
                .sessionId(sessionId)
                .persistenceProvider(persistence)
                .build();

        var lifecycle = new TodoLifecycle();
        var queryRef = new AtomicReference<>("test query");
        lifecycle.beforeAgentRun(queryRef, context);

        // Verify todos loaded into context
        var todos = context.getCustomVariable(WriteTodosTool.TODOS_CONTEXT_KEY);
        assertNotNull(todos);
        assertTrue(todos instanceof List);
        @SuppressWarnings("unchecked")
        var todoList = (List<WriteTodosTool.Todo>) todos;
        assertEquals(2, todoList.size());
        assertEquals("task one", todoList.get(0).content);

        // Verify system prompt variable set
        var todosPrompt = context.getCustomVariable(SystemVariables.AGENT_WRITE_TODOS_SYSTEM_PROMPT);
        assertNotNull(todosPrompt);
    }

    @Test
    void todoLifecycleDoesNothingWhenNoTodosPersisted() {
        var persistence = new InMemoryPersistenceProvider();
        var context = ExecutionContext.builder()
                .sessionId("empty-session")
                .persistenceProvider(persistence)
                .build();

        var lifecycle = new TodoLifecycle();
        var queryRef = new AtomicReference<>("test query");
        lifecycle.beforeAgentRun(queryRef, context);

        assertFalse(context.hasCustomVariable(WriteTodosTool.TODOS_CONTEXT_KEY));
    }

    @Test
    void todoLifecycleDoesNothingWithoutPersistenceProvider() {
        var context = ExecutionContext.builder()
                .sessionId("no-persistence")
                .build();

        var lifecycle = new TodoLifecycle();
        var queryRef = new AtomicReference<>("test query");
        lifecycle.beforeAgentRun(queryRef, context);

        assertFalse(context.hasCustomVariable(WriteTodosTool.TODOS_CONTEXT_KEY));
    }

    @Test
    void endToEndTodoPersistAndRestore() {
        var persistence = new InMemoryPersistenceProvider();
        var sessionId = "e2e-session";

        // Step 1: Create agent with WriteTodosTool, run it to create todos
        var provider1 = new MockLLMProvider();
        var todosArg = "[{\"content\":\"implement feature\",\"status\":\"IN_PROGRESS\"},{\"content\":\"write tests\",\"status\":\"PENDING\"}]";
        provider1.addResponse(CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.TOOL_CALLS,
                        Message.of(RoleType.ASSISTANT, "", null, null,
                                List.of(FunctionCall.of("call_1", "function", "write_todos",
                                        "{\"todos\":" + todosArg + "}")))
                )),
                new Usage(10, 20, 30)
        ));
        provider1.addResponse(CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "Tasks created"))),
                new Usage(10, 20, 30)
        ));

        var agent1 = Agent.builder()
                .llmProvider(provider1)
                .toolCalls(Functions.from(new WriteTodosTool(), "writeTodos"))
                .maxTurn(2)
                .build();

        var context1 = ExecutionContext.builder()
                .sessionId(sessionId)
                .persistenceProvider(persistence)
                .build();
        agent1.run("create tasks", context1);

        // Verify todos were persisted
        assertTrue(persistence.load("todos:" + sessionId).isPresent());

        // Step 2: Create a new agent and simulate session restore
        var provider2 = new MockLLMProvider();
        provider2.addResponse(CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "Continuing tasks"))),
                new Usage(10, 20, 30)
        ));

        var context2 = ExecutionContext.builder()
                .sessionId(sessionId)
                .persistenceProvider(persistence)
                .build();

        // Manually call TodoLifecycle to simulate restore
        var lifecycle = new TodoLifecycle();
        var queryRef = new AtomicReference<>("continue work");
        lifecycle.beforeAgentRun(queryRef, context2);

        // Verify todos were loaded
        var todos = context2.getCustomVariable(WriteTodosTool.TODOS_CONTEXT_KEY);
        assertNotNull(todos);
        @SuppressWarnings("unchecked")
        var todoList = (List<WriteTodosTool.Todo>) todos;
        assertEquals(2, todoList.size());
        assertEquals("implement feature", todoList.get(0).content);
        assertEquals(WriteTodosTool.Status.IN_PROGRESS, todoList.get(0).status);
    }
}
