package ai.core.server.asynctask;

import ai.core.session.BackgroundTaskManager;
import ai.core.session.BackgroundTaskStore;
import ai.core.session.SessionCommandQueue;
import ai.core.tool.subagent.SubagentOutputSink;
import core.framework.db.Database;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ServerBackgroundTaskPersistenceTest {
    @Test
    void openTaskFromPreviousPodTurnsLostOnPollWithoutDuplicateNotification() {
        var store = new InMemoryBackgroundTaskStore();
        store.saveRunning("bg-orphan-1", "sess-1", "/workspace/.core-ai/tasks/bg-orphan-1.out");

        // simulate server restart: fresh BackgroundTaskManager has empty in-memory maps
        var queue = new SessionCommandQueue();
        var restarted = new BackgroundTaskManager(queue, InMemorySink::new, "sess-1", event -> {
        }, null, store);

        assertTrue(restarted.hasTask("bg-orphan-1"));
        var polled = restarted.pollTask("bg-orphan-1");
        assertTrue(polled.isFailed());
        assertTrue(polled.getResult().contains("SERVER_TASK_LOST"));
        assertEquals("lost", store.find("sess-1", "bg-orphan-1").orElseThrow().status());
        assertTrue(store.find("sess-1", "bg-orphan-1").orElseThrow().notified());
        assertTrue(queue.drainSameMode().isEmpty(), "marking lost on restart must not emit duplicate notifications");
    }

    @Test
    void completedTaskRemainsQueryableAfterRestart() {
        var store = new InMemoryBackgroundTaskStore();
        var queue = new SessionCommandQueue();
        var original = new BackgroundTaskManager(queue, InMemorySink::new, "sess-2", event -> {
        }, null, store);

        original.submit("bg-done-1", () -> "subagent-answer", null);
        assertTrue(original.awaitTask("bg-done-1", 5000).isCompleted());

        var restarted = new BackgroundTaskManager(new SessionCommandQueue(), InMemorySink::new, "sess-2", event -> {
        }, null, store);
        var polled = restarted.pollTask("bg-done-1");
        assertTrue(polled.isCompleted());
        assertTrue(polled.getResult().contains("subagent-answer"));
    }

    @Test
    void pgStoreUsesParameterizedSqlWithoutSingleQuotes() {
        var db = mock(Database.class);
        var store = new PgServerBackgroundTaskStore();
        store.database = db;

        store.saveRunning("t1", "s1", "/workspace/.core-ai/tasks/t1.out");
        store.markPodOpenTasksLost();
        store.purgeTerminalOlderThan(Duration.ofHours(24));

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(db, atLeastOnce()).execute(sqlCaptor.capture(), any(Object[].class));
        for (var sql : sqlCaptor.getAllValues()) {
            assertFalse(sql.contains("'"), "core-ng forbids single quotes in SQL: " + sql);
        }
    }

    private static final class InMemoryBackgroundTaskStore implements BackgroundTaskStore {
        private final Map<String, StoredTask> rows = new ConcurrentHashMap<>();

        @Override
        public void saveRunning(String taskId, String sessionId, String outputRef) {
            rows.put(taskId, new StoredTask(taskId, sessionId, "running", null, null, outputRef, false));
        }

        @Override
        public void saveTerminal(StoredTask task) {
            rows.put(task.taskId(), task);
        }

        @Override
        public void markNotified(String taskId) {
            rows.computeIfPresent(taskId, (id, existing) -> new StoredTask(existing.taskId(), existing.sessionId(),
                    existing.status(), existing.result(), existing.error(), existing.outputRef(), true));
        }

        @Override
        public Optional<StoredTask> find(String sessionId, String taskId) {
            var task = rows.get(taskId);
            return task != null && sessionId.equals(task.sessionId()) ? Optional.of(task) : Optional.empty();
        }
    }

    private record InMemorySink(String taskId) implements SubagentOutputSink {
        @Override
        public void write(String content) {
        }

        @Override
        public String getReference() {
            return "/workspace/.core-ai/tasks/" + taskId + ".out";
        }

        @Override
        public void close() {
        }
    }
}
