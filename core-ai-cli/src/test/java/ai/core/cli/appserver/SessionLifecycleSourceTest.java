package ai.core.cli.appserver;

import ai.core.session.InProcessAgentSession;
import ai.core.session.lifecycle.TaskRunCoordinator;
import ai.core.session.lifecycle.TaskRunOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SessionLifecycleSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void journalsTheRunAndAnswersBindAfterTheEndedFact() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-test");
        var session = mock(InProcessAgentSession.class);
        when(session.taskRuns()).thenReturn(coordinator);
        when(session.isTurnRunning()).thenReturn(Boolean.FALSE);
        var events = new ArrayList<JsonNode>();
        NotificationSink sink = (method, params) -> {
            if ("session/lifecycle".equals(method)) events.add(params.path("event"));
        };
        try (var source = new SessionLifecycleSource("session-1", session, tempDir, () -> sink)) {
            var opened = coordinator.openRoot("request-1");
            coordinator.requestQueued();
            coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.Collections.singletonList(null));
            coordinator.taskLaunched("task-1", 7L);
            coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);

            assertNotNull(coordinator.activeSnapshot(), "coordinator lost the run before inspect");
            var active = source.inspect().path("activeTaskRun");
            assertEquals(opened.taskRunId(), active.path("taskRunId").asText());
            assertEquals("awaiting_async", active.path("state").asText());

            coordinator.taskSettled("task-1", 7L, TaskRunOutcome.RETURNED, true, "completed");
            coordinator.notificationQueued("task-1", 7L, true);
            coordinator.executionStarted("turn-2", TaskRunCoordinator.ExecutionKind.TASK_NOTIFICATION, java.util.List.of());
            coordinator.executionEnded("turn-2", TaskRunCoordinator.ExecutionKind.TASK_NOTIFICATION, TaskRunOutcome.RETURNED, null);

            var ended = source.inspect().path("lastEndedTaskRun");
            assertNotNull(ended);
            assertEquals("returned", ended.path("ended").path("outcome").asText());
            assertFalse(events.isEmpty());
            assertEquals("task_run.ended", events.getLast().path("type").asText());

            var bind = source.bind("watch-1", opened.taskRunId(), opened.runGeneration(), 0);
            assertEquals("already_ended", bind.path("status").asText());
        }
    }

    @Test
    void aQueuedRunIsVisibleToInspectAndCanBeBound() {
        var coordinator = new TaskRunCoordinator("session-queued", "inc-test");
        var session = mock(InProcessAgentSession.class);
        when(session.taskRuns()).thenReturn(coordinator);
        when(session.isTurnRunning()).thenReturn(Boolean.FALSE);
        var sink = mock(NotificationSink.class);
        try (var source = new SessionLifecycleSource("session-queued", session, tempDir, () -> sink)) {
            coordinator.openRoot("request-1", "turn-1");
            coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.List.of("turn-1"));
            var queued = coordinator.openRoot("request-2", "turn-2");

            var inspect = source.inspect();
            var refs = inspect.path("queuedTaskRunRefs");
            assertEquals(1, refs.size());
            assertEquals(queued.taskRunId(), refs.get(0).path("taskRunId").asText());
            assertEquals("queued", refs.get(0).path("state").asText());

            var bind = source.bind("watch-queued", queued.taskRunId(), queued.runGeneration(), 0);
            assertEquals("bound", bind.path("status").asText());
            assertEquals("queued", bind.path("run").path("state").asText());
        }
    }

    @Test
    void aPreviousUnfinishedRunIsRecordedAsInterruptedOnOpen() {
        var coordinator = new TaskRunCoordinator("session-2", "inc-test");
        var session = mock(InProcessAgentSession.class);
        when(session.taskRuns()).thenReturn(coordinator);
        when(session.isTurnRunning()).thenReturn(Boolean.FALSE);
        var sink = mock(NotificationSink.class);

        var journal = LifecycleJournal.open(tempDir, "session-2");
        var run = ai.core.utils.JsonUtil.OBJECT_MAPPER.createObjectNode();
        run.put("sessionId", "session-2");
        run.put("taskRunId", "run-old");
        run.put("runGeneration", 4);
        run.put("startedAtMs", System.currentTimeMillis() - 10_000);
        run.put("coverage", "complete");
        var payload = ai.core.utils.JsonUtil.OBJECT_MAPPER.createObjectNode();
        payload.set("run", run);
        journal.append("run_registered", payload);
        journal.close();

        try (var source = new SessionLifecycleSource("session-2", session, tempDir, () -> sink)) {
            var ended = source.inspect().path("lastEndedTaskRun");
            assertEquals("interrupted", ended.path("ended").path("outcome").asText());
            assertEquals("engine_restarted", ended.path("ended").path("reason").asText());
        }
    }
}
