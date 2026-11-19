package ai.core.server.home;

import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.HomeTodoDigestRun;
import ai.core.server.domain.UserTodo;
import ai.core.server.session.ChatMessageQuery;
import ai.core.server.session.ChatMessageStore;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.web.foryou.UserTodoStore;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HomeTodoDigestServiceTest {
    private static final LocalDate RUN_DATE = LocalDate.of(2026, 11, 18);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void writesDeterministicAutoTodosWithProvenance() {
        var fixture = fixture();
        fixture.service.output = """
                {"todos":[
                  {"session":"S1","title":"Reply to the tax office","description":"deadline Friday","priority":"high"},
                  {"session":"S1","title":"Send the signed contract","description":"copy to legal","priority":"low"}
                ]}
                """;

        fixture.service.processUser("u-1", List.of(session("s-1")), batch());

        assertEquals(2, fixture.todos.size());
        var first = fixture.todos.getFirst();
        assertEquals("AUTO", first.source);
        assertEquals("2026-11-18", first.originDate);
        assertEquals(List.of("s-1"), first.originSessionIds);
        assertEquals("high", first.priority);
        assertEquals("test-model", first.generatedBy);
        assertTrue(first.id.startsWith("auto-20261118-"));
        assertTrue(first.dedupeKey.startsWith("auto:2026-11-18:s-1:"));
        assertFalse(first.completed);

        var run = fixture.digest.runs.get("u-1:2026-11-18");
        assertNotNull(run);
        assertEquals("COMPLETED", run.status);
        assertEquals(2, run.todoCount);
        assertEquals(0, run.skippedCount);
        assertEquals(1, run.sessionCount);
        assertEquals(2, run.messageCount);
    }

    @Test
    void theSameDayProducesTheSameTodoIds() {
        var firstRun = fixture();
        firstRun.service.output = "{\"todos\":[{\"session\":\"S1\",\"title\":\"Reply to the tax office\"}]}";
        firstRun.service.processUser("u-1", List.of(session("s-1")), batch());

        var secondRun = fixture();
        secondRun.service.output = "{\"todos\":[{\"session\":\"S1\",\"title\":\"  Reply   to the tax office \"}]}";
        secondRun.service.processUser("u-1", List.of(session("s-1")), batch());

        assertEquals(firstRun.todos.getFirst().id, secondRun.todos.getFirst().id);
        assertEquals(firstRun.todos.getFirst().dedupeKey, secondRun.todos.getFirst().dedupeKey);
    }

    @Test
    void aCompletedRunIsNeverRepeated() {
        var fixture = fixture();
        var run = new HomeTodoDigestRun();
        run.id = "u-1:2026-11-18";
        run.userId = "u-1";
        run.runDate = "2026-11-18";
        run.status = "COMPLETED";
        run.startedAt = RUN_DATE.atStartOfDay(ZONE);
        fixture.digest.runs.put(run.id, run);
        fixture.service.output = "{\"todos\":[{\"session\":\"S1\",\"title\":\"should not exist\"}]}";

        fixture.service.processUser("u-1", List.of(session("s-1")), batch());

        assertEquals(0, fixture.service.completions);
        assertTrue(fixture.todos.isEmpty());
    }

    @Test
    void aFailedRunRetries() {
        var fixture = fixture();
        var run = new HomeTodoDigestRun();
        run.id = "u-1:2026-11-18";
        run.userId = "u-1";
        run.runDate = "2026-11-18";
        run.status = "FAILED";
        run.error = "previous failure";
        run.startedAt = RUN_DATE.atStartOfDay(ZONE);
        fixture.digest.runs.put(run.id, run);
        fixture.service.output = "{\"todos\":[{\"session\":\"S1\",\"title\":\"Retry item\"}]}";

        fixture.service.processUser("u-1", List.of(session("s-1")), batch());

        assertEquals(1, fixture.service.completions);
        assertEquals(1, fixture.todos.size());
        var retried = fixture.digest.runs.get("u-1:2026-11-18");
        assertEquals("COMPLETED", retried.status);
        assertNull(retried.error);
    }

    @Test
    void sessionsWithoutUserMessagesAreSkipped() {
        var fixture = fixture();
        doReturn(List.of(message("agent", "scheduled job ran"))).when(fixture.messageStore).find(any());

        fixture.service.processUser("u-1", List.of(session("s-1")), batch());

        assertEquals(0, fixture.service.completions);
        assertTrue(fixture.todos.isEmpty());
        assertEquals("SKIPPED", fixture.digest.runs.get("u-1:2026-11-18").status);
    }

    @Test
    void invalidModelOutputFailsWithoutWritingTodos() {
        var fixture = fixture();
        fixture.service.output = "I could not summarize this.";

        fixture.service.processUser("u-1", List.of(session("s-1")), batch());

        assertTrue(fixture.todos.isEmpty());
        var run = fixture.digest.runs.get("u-1:2026-11-18");
        assertEquals("FAILED", run.status);
        assertTrue(run.error.contains("not JSON"));
    }

    @Test
    void duplicateTitlesInOneRunAreCountedAsSkipped() {
        var fixture = fixture();
        fixture.service.output = """
                {"todos":[
                  {"session":"S1","title":"Reply to the tax office"},
                  {"session":"S2","title":"Reply to the tax office"}
                ]}
                """;

        fixture.service.processUser("u-1", List.of(session("s-1"), session("s-2")), batch());

        assertEquals(1, fixture.todos.size());
        assertEquals("s-1", fixture.todos.getFirst().originSessionIds.getFirst());
        var run = fixture.digest.runs.get("u-1:2026-11-18");
        assertEquals(1, run.todoCount);
        assertEquals(1, run.skippedCount);
    }

    @Test
    void aTodoIsAttributedToTheSessionTheModelNames() {
        var fixture = fixture();
        fixture.service.output = "{\"todos\":[{\"session\":\"S2\",\"title\":\"Follow up with finance\"}]}";

        fixture.service.processUser("u-1", List.of(session("s-1"), session("s-2")), batch());

        assertEquals(List.of("s-2"), fixture.todos.getFirst().originSessionIds);
        assertTrue(fixture.todos.getFirst().dedupeKey.contains(":s-2:"));
    }

    @Test
    void unknownSessionLabelsFallBackToTheFirstSession() {
        var fixture = fixture();
        fixture.service.output = "{\"todos\":[{\"session\":\"S9\",\"title\":\"Check invoices\"}]}";

        fixture.service.processUser("u-1", List.of(session("s-1"), session("s-2")), batch());

        assertEquals(List.of("s-1"), fixture.todos.getFirst().originSessionIds);
    }

    @Test
    void existingHistoricalTodosReadAsManual() {
        var todo = JsonUtil.fromJson(UserTodo.class, """
                {"id":"t-1","userId":"u-1","title":"Legacy item","completed":false,
                 "createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:00Z"}
                """);
        assertEquals("MANUAL", todo.source);
        assertNull(todo.dedupeKey);
    }

    private Fixture fixture() {
        var fixture = new Fixture();
        fixture.service = new TestService();
        fixture.service.chatSessionStore = mock(ChatSessionStore.class);
        fixture.service.chatMessageStore = fixture.messageStore;
        fixture.service.todoStore = fixture.todoStore;
        fixture.service.digestStore = fixture.digest;
        when(fixture.messageStore.find(any())).thenAnswer(invocation -> {
            var query = (ChatMessageQuery) invocation.getArgument(0);
            return switch (query.sessionId) {
                case "s-1" -> List.of(message("user", "Help me plan the tax filing"), message("agent", "Draft is ready"));
                case "s-2" -> List.of(message("user", "Send the contract to finance"), message("agent", "Sent"));
                default -> List.of();
            };
        });
        return fixture;
    }

    private HomeTodoDigestService.DigestBatch batch() {
        return new HomeTodoDigestService.DigestBatch(RUN_DATE, RUN_DATE.atStartOfDay(ZONE),
                RUN_DATE.plusDays(1).atStartOfDay(ZONE), "test-model");
    }

    private ChatSession session(String id) {
        var session = new ChatSession();
        session.id = id;
        session.userId = "u-1";
        session.title = "Session " + id;
        session.source = "chat";
        return session;
    }

    private ChatMessage message(String role, String content) {
        var message = new ChatMessage();
        message.id = role + "-" + content.hashCode();
        message.role = role;
        message.content = content;
        return message;
    }

    private static final class Fixture {
        final List<UserTodo> todos = new ArrayList<>();
        final MemoryTodoStore todoStore = new MemoryTodoStore(todos);
        final ChatMessageStore messageStore = mock(ChatMessageStore.class);
        final MemoryDigestStore digest = new MemoryDigestStore();
        TestService service;
    }

    private static final class TestService extends HomeTodoDigestService {
        String output;
        int completions;

        @Override
        protected String complete(String prompt, String model) {
            completions++;
            return output;
        }
    }

    private static final class MemoryTodoStore implements UserTodoStore {
        private final List<UserTodo> todos;

        private MemoryTodoStore(List<UserTodo> todos) {
            this.todos = todos;
        }

        @Override
        public Optional<UserTodo> get(String id) {
            return todos.stream().filter(todo -> todo.id.equals(id)).findFirst();
        }

        @Override
        public List<UserTodo> list(String userId) {
            return todos;
        }

        @Override
        public List<UserTodo> active(String userId) {
            return todos;
        }

        @Override
        public long count(String userId) {
            return todos.size();
        }

        @Override
        public long activeCount(String userId) {
            return todos.size();
        }

        @Override
        public void insert(UserTodo todo) {
            todos.add(todo);
        }

        @Override
        public boolean insertIfAbsent(UserTodo todo) {
            if (get(todo.id).isPresent()) return false;
            todos.add(todo);
            return true;
        }

        @Override
        public void replace(UserTodo todo) {
            todos.removeIf(existing -> existing.id.equals(todo.id));
            todos.add(todo);
        }

        @Override
        public void delete(String id) {
            todos.removeIf(todo -> todo.id.equals(id));
        }
    }

    private static final class MemoryDigestStore implements HomeTodoDigestStore {
        final Map<String, HomeTodoDigestRun> runs = new HashMap<>();

        @Override
        public Optional<HomeTodoDigestRun> get(String id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public boolean insertIfAbsent(HomeTodoDigestRun run) {
            if (runs.containsKey(run.id)) return false;
            runs.put(run.id, run);
            return true;
        }

        @Override
        public void replace(HomeTodoDigestRun run) {
            runs.put(run.id, run);
        }
    }
}
