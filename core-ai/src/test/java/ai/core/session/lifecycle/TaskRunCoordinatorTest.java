package ai.core.session.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class TaskRunCoordinatorTest {
    @Test
    void aSingleRootExecutionEndsOnlyWhenTheDriverHasReleasedTheTurn() {
        var pending = new AtomicBoolean(true);
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);
        coordinator.setPendingWorkProbe(pending::get);

        coordinator.openRoot("request-1");
        coordinator.requestQueued();
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, 1);
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);

        assertEquals(1, recorder.registered.size());
        assertTrue(recorder.ended.isEmpty());
        assertEquals(TaskRunState.DRAINING, coordinator.snapshot().state());

        pending.set(false);
        coordinator.pendingWorkChanged();

        assertEquals(1, recorder.ended.size());
        assertEquals(TaskRunOutcome.RETURNED, recorder.ended.getFirst().outcome());
        assertEquals(TaskRunState.TERMINAL, coordinator.snapshot().state());
    }

    @Test
    void requiredBackgroundWorkAndItsContinuationKeepTheRunOpenUntilBothFinish() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);

        coordinator.openRoot("request-1");
        coordinator.requestQueued();
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, 1);
        coordinator.taskLaunched("task-1", 7L);
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);

        assertTrue(recorder.ended.isEmpty());
        assertEquals(TaskRunState.AWAITING_ASYNC, coordinator.snapshot().state());

        coordinator.taskSettled("task-1", 7L, TaskRunOutcome.RETURNED, true, "completed");
        coordinator.notificationQueued("task-1", 7L, true);
        assertTrue(recorder.ended.isEmpty());
        assertEquals(1, coordinator.snapshot().queuedContinuations());

        coordinator.executionStarted("turn-2", TaskRunCoordinator.ExecutionKind.TASK_NOTIFICATION, 0);
        assertEquals(0, coordinator.snapshot().queuedContinuations());
        coordinator.executionEnded("turn-2", TaskRunCoordinator.ExecutionKind.TASK_NOTIFICATION, TaskRunOutcome.RETURNED, null);

        assertEquals(1, recorder.ended.size());
        assertEquals(TaskRunOutcome.RETURNED, recorder.ended.getFirst().outcome());
    }

    @Test
    void anUnhandledBackgroundFailureMakesTheRunFailed() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);

        coordinator.openRoot("request-1");
        coordinator.requestQueued();
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, 1);
        coordinator.taskLaunched("task-1", 1L);
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);
        coordinator.taskSettled("task-1", 1L, TaskRunOutcome.FAILED, false, "command failed");

        assertEquals(1, recorder.ended.size());
        assertEquals(TaskRunOutcome.FAILED, recorder.ended.getFirst().outcome());
        assertEquals("command failed", recorder.ended.getFirst().reason());
    }

    @Test
    void aRejectedContinuationNeedsAttentionInsteadOfReturning() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);

        coordinator.openRoot("request-1");
        coordinator.requestQueued();
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, 1);
        coordinator.taskLaunched("task-1", 1L);
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);
        coordinator.taskSettled("task-1", 1L, TaskRunOutcome.RETURNED, true, "completed");
        coordinator.notificationQueued("task-1", 1L, false);

        assertTrue(recorder.ended.isEmpty());
        assertEquals(TaskRunState.NEEDS_ATTENTION, coordinator.snapshot().state());
        assertEquals("continuation_queue_rejected", coordinator.snapshot().attentionReason());
    }

    @Test
    void untrackableSourcesCannotClaimReturned() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);

        coordinator.openRoot("request-1");
        coordinator.requestQueued();
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, 1);
        coordinator.markUntrackable("async_tool_executor_not_wired");
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);

        assertTrue(recorder.ended.isEmpty());
        assertEquals(TaskRunState.NEEDS_ATTENTION, coordinator.snapshot().state());
    }

    @Test
    void cancelWaitsForRequiredWorkAndKeepsTheCancelledOutcome() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);

        coordinator.openRoot("request-1");
        coordinator.requestQueued();
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, 1);
        coordinator.taskLaunched("task-1", 1L);
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);
        coordinator.cancelRun("user requested stop");
        assertTrue(recorder.ended.isEmpty());

        coordinator.taskSettled("task-1", 1L, TaskRunOutcome.CANCELLED, false, "cancelled");
        assertEquals(1, recorder.ended.size());
        assertEquals(TaskRunOutcome.CANCELLED, recorder.ended.getFirst().outcome());
    }

    @Test
    void closingTheSessionInterruptsAnActiveRun() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);

        coordinator.openRoot("request-1");
        coordinator.interrupt("session_closed");

        assertEquals(1, recorder.ended.size());
        assertEquals(TaskRunOutcome.INTERRUPTED, recorder.ended.getFirst().outcome());
        assertEquals(TaskRunState.TERMINAL, coordinator.snapshot().state());
    }

    @Test
    void aDroppedConsumedNotificationReleasesItsContinuation() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);

        coordinator.openRoot("request-1");
        coordinator.requestQueued();
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, 1);
        coordinator.taskLaunched("task-1", 1L);
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);
        coordinator.taskSettled("task-1", 1L, TaskRunOutcome.RETURNED, true, "completed");
        coordinator.notificationQueued("task-1", 1L, true);
        coordinator.notificationDropped("task-1", 1L);

        assertEquals(1, recorder.ended.size());
        assertEquals(TaskRunOutcome.RETURNED, recorder.ended.getFirst().outcome());
    }

    @Test
    void aFreshRunDoesNotReuseThePreviousIdentity() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        coordinator.openRoot("request-1");
        var first = coordinator.snapshot();
        coordinator.interrupt("session_closed");
        coordinator.openRoot("request-2");
        var second = coordinator.snapshot();

        assertNotNull(first.taskRunId());
        assertNotNull(second.taskRunId());
        assertFalse(first.taskRunId().equals(second.taskRunId()));
        assertNull(second.endedAtMs());
    }

    private static final class Recorder implements TaskRunObserver {
        private final List<TaskRunSnapshot> registered = new ArrayList<>();
        private final List<TaskRunSnapshot> changed = new ArrayList<>();
        private final List<TaskRunEnded> ended = new ArrayList<>();

        @Override
        public void onTaskRunRegistered(TaskRunSnapshot snapshot) {
            registered.add(snapshot);
        }

        @Override
        public void onTaskRunChanged(TaskRunSnapshot snapshot) {
            changed.add(snapshot);
        }

        @Override
        public void onTaskRunEnded(TaskRunSnapshot snapshot, TaskRunEnded event) {
            ended.add(event);
        }
    }
}
