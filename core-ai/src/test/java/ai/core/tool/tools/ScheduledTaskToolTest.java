package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ScheduledTaskToolTest {

    private InMemoryScheduledTaskStore store;
    private ScheduledTaskTool tool;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        store = new InMemoryScheduledTaskStore();
        tool = ScheduledTaskTool.builder(store).build();
        context = ExecutionContext.builder()
                .sessionId("test-session")
                .userId("test-user")
                .build();
    }

    @Test
    void createRequiresCronAndInput() {
        var result = tool.execute("""
                {"action":"create","cron_expression":"0 9 * * *"}""", context);
        assertTrue(result.isFailed(), result.toString());
        assertTrue(result.getResult().contains("input is required"));

        var result2 = tool.execute("""
                {"action":"create","input":"check status"}""", context);
        assertTrue(result2.isFailed(), result2.toString());
        assertTrue(result2.getResult().contains("cron_expression is required"));
    }

    @Test
    void createStoresTaskBoundToSession() {
        var result = tool.execute("""
                {"action":"create","name":"daily check","cron_expression":"0 9 * * *","input":"check the status","timezone":"UTC"}""", context);
        assertFalse(result.isFailed(), result.toString());
        assertTrue(result.getResult().contains("daily check"));
        assertTrue(result.getResult().contains("next_run_at"));

        var tasks = store.list("test-session");
        assertEquals(1, tasks.size());
        var task = tasks.get(0);
        assertEquals("test-session", task.sessionId);
        assertEquals("test-user", task.userId);
        assertEquals("0 9 * * *", task.cronExpression);
        assertNotNull(task.nextRunAt);
    }

    @Test
    void listReturnsOnlyCurrentSessionTasks() {
        tool.execute("""
                {"action":"create","cron_expression":"0 9 * * *","input":"check"}""", context);

        var otherTask = new ScheduledTask();
        otherTask.id = "other";
        otherTask.sessionId = "other-session";
        otherTask.userId = "u";
        otherTask.name = "other";
        otherTask.cronExpression = "0 9 * * *";
        otherTask.timezone = "UTC";
        otherTask.input = "x";
        store.create(otherTask);

        var result = tool.execute("{\"action\":\"list\"}", context);
        assertFalse(result.isFailed(), result.toString());
        assertTrue(result.getResult().contains("check"));
        assertFalse(result.getResult().contains("other"));
    }

    @Test
    void deleteRejectsOtherSessionTask() {
        var otherTask = new ScheduledTask();
        otherTask.id = "other";
        otherTask.sessionId = "other-session";
        otherTask.userId = "u";
        otherTask.name = "other";
        otherTask.cronExpression = "0 9 * * *";
        otherTask.timezone = "UTC";
        otherTask.input = "x";
        store.create(otherTask);

        var result = tool.execute("{\"action\":\"delete\",\"id\":\"other\"}", context);
        assertTrue(result.isFailed(), result.toString());
        assertTrue(result.getResult().contains("another session"));
        assertNotNull(store.get("other"));
    }

    @Test
    void deleteRemovesOwnTask() {
        var created = tool.execute("""
                {"action":"create","cron_expression":"0 9 * * *","input":"check"}""", context);
        var id = extractId(created.getResult());

        var result = tool.execute("{\"action\":\"delete\",\"id\":\"" + id + "\"}", context);
        assertFalse(result.isFailed(), result.toString());
        assertNull(store.get(id));
    }

    @Test
    void invalidActionRejected() {
        var result = tool.execute("{\"action\":\"pause\"}", context);
        assertTrue(result.isFailed(), result.toString());
        assertTrue(result.getResult().contains("invalid action"));
    }

    @Test
    void buildTriggerMessagePrefixesInput() {
        var task = new ScheduledTask();
        task.name = "daily";
        task.input = "check the status";
        assertEquals("[Scheduled task: daily] check the status", ScheduledTaskTool.buildTriggerMessage(task));

        task.input = "";
        assertEquals("[Scheduled task: daily]", ScheduledTaskTool.buildTriggerMessage(task));
    }

    private String extractId(String message) {
        for (var line : message.split("\n")) {
            if (line.startsWith("id: ")) return line.substring(4).trim();
        }
        throw new AssertionError("no id in message: " + message);
    }

    static class InMemoryScheduledTaskStore implements ScheduledTaskStore {
        private final Map<String, ScheduledTask> tasks = new ConcurrentHashMap<>();

        @Override
        public ScheduledTask create(ScheduledTask task) {
            var now = ZonedDateTime.now();
            task.nextRunAt = now.plusDays(1);
            task.createdAt = now;
            task.updatedAt = now;
            tasks.put(task.id, task);
            return task;
        }

        @Override
        public List<ScheduledTask> list(String sessionId) {
            return tasks.values().stream().filter(t -> t.sessionId.equals(sessionId)).toList();
        }

        @Override
        public ScheduledTask get(String id) {
            return tasks.get(id);
        }

        @Override
        public boolean delete(String id) {
            return tasks.remove(id) != null;
        }

        @Override
        public List<ScheduledTask> findDue(ZonedDateTime now) {
            return new ArrayList<>(tasks.values());
        }

        @Override
        public boolean claim(String id, ZonedDateTime expectedNextRunAt, ZonedDateTime newNextRunAt) {
            var task = tasks.get(id);
            if (task == null || task.nextRunAt == null || !task.nextRunAt.isEqual(expectedNextRunAt)) return false;
            task.nextRunAt = newNextRunAt;
            return true;
        }
    }
}
