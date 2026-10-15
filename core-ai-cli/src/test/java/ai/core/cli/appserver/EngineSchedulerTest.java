package ai.core.cli.appserver;

import ai.core.cli.schedule.FileScheduledTaskStore;
import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class EngineSchedulerTest {

    @TempDir
    Path tempDir;

    private FileScheduledTaskStore store(Path workspace) {
        return new FileScheduledTaskStore(tempDir.resolve("schedules.json"), workspace);
    }

    /**
     * Creates a task and persists a due {@code nextRunAt} through the claim path, so every store
     * instance (including a scheduler loading its own snapshot) sees it as genuinely due.
     */
    private ScheduledTask dueTask(ScheduledTaskStore store, String sessionId) {
        var task = new ScheduledTask();
        task.id = UUID.randomUUID().toString();
        task.sessionId = sessionId;
        task.name = "daily";
        task.cronExpression = "0 9 * * *";
        task.timezone = "UTC";
        task.input = "check status";
        task.enabled = Boolean.TRUE;
        var created = store.create(task);
        assertTrue(store.claim(created.id, created.nextRunAt, ZonedDateTime.now().minusMinutes(1)));
        return store.get(created.id);
    }

    @Test
    void tickFiresDueOwnedTaskAndAdvancesNextRun() {
        var workspace = tempDir.resolve("ws-a");
        var store = store(workspace);
        var task = dueTask(store, "s-1");
        var sessions = new FakeSessions();
        var session = new FakeSession();
        sessions.live.put("s-1", session);
        var sink = new FakeSink();
        var scheduler = new EngineScheduler(store, workspace, sessions, () -> sink);

        scheduler.tick();
        scheduler.tick();

        assertEquals(1, session.sent.size());
        assertTrue(session.sent.getFirst().startsWith("[Scheduled task: daily]"));
        var stored = store.get(task.id);
        assertNotNull(stored.lastRunAt);
        assertEquals("fired", stored.lastRunStatus);
        assertEquals(1, stored.runCount);
        assertTrue(stored.nextRunAt.isAfter(ZonedDateTime.now()));
        assertEquals(1, store.runs(task.id, 10).size());
        assertTrue(sink.events.contains("schedule/fired"));
        // claim/recordRun never bump updatedAt, so no spurious changed event follows the fire
        assertTrue(sink.events.stream().noneMatch("schedule/changed"::equals));
    }

    @Test
    void tickResumesSessionNotInMemory() {
        var workspace = tempDir.resolve("ws-a");
        var store = store(workspace);
        dueTask(store, "s-1");
        var sessions = new FakeSessions();
        var session = new FakeSession();
        sessions.resumable.put("s-1", session);
        var scheduler = new EngineScheduler(store, workspace, sessions, () -> new FakeSink());

        scheduler.tick();

        assertEquals(1, session.sent.size());
    }

    @Test
    void missingSessionRecordsRunAndAutoDisables() {
        var workspace = tempDir.resolve("ws-a");
        var store = store(workspace);
        var task = dueTask(store, "s-1");
        var sink = new FakeSink();
        var scheduler = new EngineScheduler(store, workspace, new FakeSessions(), () -> sink);

        scheduler.tick();

        var stored = store.get(task.id);
        assertEquals("session_missing", stored.lastRunStatus);
        assertEquals(Boolean.FALSE, stored.enabled);
        assertEquals("session_missing", stored.disabledReason);
        assertEquals(1, store.runs(task.id, 10).size());
        assertEquals("session_missing", store.runs(task.id, 1).getFirst().status);
        assertTrue(sink.events.contains("schedule/fired"));
    }

    @Test
    void ignoresTasksOfOtherWorkspaces() {
        var file = tempDir.resolve("schedules.json");
        var otherStore = new FileScheduledTaskStore(file, tempDir.resolve("ws-b"));
        var task = dueTask(otherStore, "s-9");
        var sessions = new FakeSessions();
        sessions.live.put("s-9", new FakeSession());
        var sink = new FakeSink();
        var scheduler = new EngineScheduler(store(tempDir.resolve("ws-a")), tempDir.resolve("ws-a"), sessions, () -> sink);

        scheduler.tick();

        assertTrue(sessions.live.get("s-9").sent.isEmpty());
        assertTrue(otherStore.runs(task.id, 10).isEmpty());
        assertTrue(sink.events.isEmpty());
    }

    @Test
    void triggerNowRecordsManualRunWithoutClaiming() {
        var workspace = tempDir.resolve("ws-a");
        var store = store(workspace);
        var task = dueTask(store, "s-1");
        var scheduledNext = task.nextRunAt;
        var sessions = new FakeSessions();
        sessions.live.put("s-1", new FakeSession());
        var scheduler = new EngineScheduler(store, workspace, sessions, () -> new FakeSink());

        var run = scheduler.triggerNow(task.id);

        assertTrue(run.manual);
        assertNull(run.scheduledFor);
        assertEquals("fired", run.status);
        assertEquals(scheduledNext, store.get(task.id).nextRunAt);
        assertEquals(1, store.runs(task.id, 10).size());
    }

    @Test
    void triggerNowFailsForMissingSessionAndForeignWorkspace() {
        var workspace = tempDir.resolve("ws-a");
        var store = store(workspace);
        var task = dueTask(store, "s-1");
        var scheduler = new EngineScheduler(store, workspace, new FakeSessions(), () -> new FakeSink());

        var missing = assertThrows(RpcException.class, () -> scheduler.triggerNow(task.id));
        assertEquals("SESSION_NOT_FOUND", missing.data().path("code").asText());

        var file = tempDir.resolve("schedules.json");
        var otherStore = new FileScheduledTaskStore(file, tempDir.resolve("ws-b"));
        var foreign = dueTask(otherStore, "s-9");
        var foreignScheduler = new EngineScheduler(store(workspace), workspace, new FakeSessions(), () -> new FakeSink());
        var wrongWorkspace = assertThrows(RpcException.class, () -> foreignScheduler.triggerNow(foreign.id));
        assertEquals("WRONG_WORKSPACE", wrongWorkspace.data().path("code").asText());
    }

    @Test
    void legacyTaskIsClaimedBySessionFileAndBackfilled() throws Exception {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        var task = dueTask(store, "s-legacy");
        assertNull(task.workspace);
        var workspace = tempDir.resolve("ws-legacy");
        var sessionsDir = tempDir.resolve("legacy-sessions");
        Files.createDirectories(sessionsDir);
        Files.writeString(sessionsDir.resolve("s-legacy.data"), "{}");
        var sessions = new FakeSessions();
        var session = new FakeSession();
        sessions.resumable.put("s-legacy", session);
        var scheduler = new TestScheduler(store, workspace, sessions, () -> new FakeSink(), sessionsDir);

        scheduler.tick();

        assertEquals(1, session.sent.size());
        assertEquals(workspace.toAbsolutePath().normalize().toString(), store.get(task.id).workspace);
    }

    @Test
    void externalCreationIsAnnouncedAsChanged() {
        var workspace = tempDir.resolve("ws-a");
        var store = store(workspace);
        var sink = new FakeSink();
        var scheduler = new EngineScheduler(store, workspace, new FakeSessions(), () -> sink);

        scheduler.tick();
        assertTrue(sink.events.isEmpty());

        var task = new ScheduledTask();
        task.id = UUID.randomUUID().toString();
        task.sessionId = "s-1";
        task.name = "external";
        task.cronExpression = "0 9 * * *";
        task.timezone = "UTC";
        task.input = "check";
        task.enabled = Boolean.TRUE;
        store.create(task);

        scheduler.tick();
        assertEquals(1, sink.events.size());
        assertEquals("schedule/changed", sink.events.getFirst());
    }

    @Test
    void queuedWhenSessionIsAlreadyRunning() {
        var workspace = tempDir.resolve("ws-a");
        var store = store(workspace);
        var task = dueTask(store, "s-1");
        var sessions = new FakeSessions();
        var session = new FakeSession();
        session.running = true;
        sessions.live.put("s-1", session);
        var scheduler = new EngineScheduler(store, workspace, sessions, () -> new FakeSink());

        scheduler.tick();

        assertEquals("queued", store.get(task.id).lastRunStatus);
        assertEquals("queued", store.runs(task.id, 1).getFirst().status);
    }

    /** Points the session-file probe at a temp directory instead of the real ~/.core-ai. */
    private static final class TestScheduler extends EngineScheduler {
        private final Path sessionsDir;

        TestScheduler(ScheduledTaskStore store, Path workspace, Sessions sessions, Supplier<NotificationSink> sink, Path sessionsDir) {
            super(store, workspace, sessions, sink);
            this.sessionsDir = sessionsDir;
        }

        @Override
        Path sessionFile(String sessionId) {
            return sessionsDir.resolve(sessionId + ".data");
        }
    }

    private static final class FakeSession implements EngineScheduler.Session {
        final List<String> sent = new ArrayList<>();
        boolean running;

        @Override
        public boolean running() {
            return running;
        }

        @Override
        public void send(String message) {
            sent.add(message);
        }
    }

    private static final class FakeSessions implements EngineScheduler.Sessions {
        final Map<String, FakeSession> live = new HashMap<>();
        final Map<String, FakeSession> resumable = new HashMap<>();

        @Override
        public EngineScheduler.Session find(String sessionId) {
            return live.get(sessionId);
        }

        @Override
        public EngineScheduler.Session resume(String sessionId) {
            var session = resumable.get(sessionId);
            if (session != null) live.put(sessionId, session);
            return session;
        }
    }

    private static final class FakeSink implements NotificationSink {
        final List<String> events = new ArrayList<>();

        @Override
        public void notify(String method, JsonNode params) {
            events.add(method);
        }
    }
}
