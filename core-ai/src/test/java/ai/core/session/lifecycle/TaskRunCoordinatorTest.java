package ai.core.session.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class TaskRunCoordinatorTest {
    @Test
    void aSecondRootWaitsAsItsOwnQueuedRunUntilTheFirstRunSettles() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var first = coordinator.openRoot("request-1", "turn-1");
        coordinator.requestQueued("turn-1");
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, List.of("turn-1"));

        var second = coordinator.openRoot("request-2", "turn-2");
        assertEquals(TaskRunState.QUEUED, second.state());
        assertEquals(first.taskRunId(), coordinator.activeSnapshot().taskRunId());
        assertTrue(coordinator.shouldDeferUserBatch(List.of("turn-2")));
        assertNull(coordinator.lastEndedSnapshot());

        coordinator.taskLaunched("job-1", 1L);
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);
        assertTrue(coordinator.shouldDeferUserBatch(List.of("turn-2")));

        coordinator.taskSettled("job-1", 1L, TaskRunOutcome.RETURNED, false, "completed");
        assertNull(coordinator.activeSnapshot());
        assertEquals(TaskRunOutcome.RETURNED, coordinator.lastEndedSnapshot().outcome());
        assertFalse(coordinator.shouldDeferUserBatch(List.of("turn-2")));

        var claimed = coordinator.claimQueued(List.of("turn-2"));
        assertEquals(second.taskRunId(), claimed.taskRunId());
        assertEquals(TaskRunState.REGISTERED, coordinator.activeSnapshot().state());
        coordinator.executionStarted("turn-2", TaskRunCoordinator.ExecutionKind.ROOT, List.of("turn-2"));
        coordinator.executionEnded("turn-2", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);
        assertEquals(TaskRunOutcome.RETURNED, coordinator.lastEndedSnapshot().outcome());
    }

    @Test
    void aDiscardedQueuedRunNeverBecomesActive() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var active = coordinator.openRoot("request-1", "turn-1");
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, List.of());
        var queued = coordinator.openRoot("request-2", "turn-2");

        coordinator.discardQueued("turn-2", "steered_into_active_run");

        assertNull(coordinator.snapshotForRun(queued.taskRunId()));
        assertEquals(active.taskRunId(), coordinator.activeSnapshot().taskRunId());
    }

    @Test
    void aNeedsAttentionRunIsFencedWhenANewQueuedRunStarts() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var attention = coordinator.openRoot("request-1", "turn-1");
        coordinator.markUntrackable("remote_media_not_wired");
        var queued = coordinator.openRoot("request-2", "turn-2");
        assertEquals(TaskRunState.QUEUED, queued.state());

        var claimed = coordinator.claimQueued(List.of("turn-2"));
        assertEquals(queued.taskRunId(), claimed.taskRunId());
        assertEquals(TaskRunOutcome.INTERRUPTED, coordinator.lastEndedSnapshot().outcome());
        assertEquals(attention.taskRunId(), coordinator.lastEndedSnapshot().taskRunId());
    }

    @Test
    void aSingleRootExecutionEndsOnlyWhenTheDriverHasReleasedTheTurn() {
        var pending = new AtomicBoolean(true);
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var recorder = new Recorder();
        coordinator.setObserver(recorder);
        coordinator.setPendingWorkProbe(pending::get);

        coordinator.openRoot("request-1");
        coordinator.requestQueued();
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.Collections.singletonList(null));
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
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.Collections.singletonList(null));
        coordinator.taskLaunched("task-1", 7L);
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);

        assertTrue(recorder.ended.isEmpty());
        assertEquals(TaskRunState.AWAITING_ASYNC, coordinator.snapshot().state());

        coordinator.taskSettled("task-1", 7L, TaskRunOutcome.RETURNED, true, "completed");
        coordinator.notificationQueued("task-1", 7L, true);
        assertTrue(recorder.ended.isEmpty());
        assertEquals(1, coordinator.snapshot().queuedContinuations());

        coordinator.executionStarted("turn-2", TaskRunCoordinator.ExecutionKind.TASK_NOTIFICATION, List.of());
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
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.Collections.singletonList(null));
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
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.Collections.singletonList(null));
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
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.Collections.singletonList(null));
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
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.Collections.singletonList(null));
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
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, java.util.Collections.singletonList(null));
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
        assertNotEquals(first.taskRunId(), second.taskRunId());
        assertNull(second.endedAtMs());
    }

    @Test
    void aQueuedRootIsNotDeferredWhileItsOwnRunAwaitsTheDriver() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var run = coordinator.openRoot("request-1", "turn-1");
        coordinator.requestQueued("turn-1");
        assertEquals(TaskRunState.AWAITING_ASYNC, coordinator.snapshot().state());
        assertEquals(run.taskRunId(), coordinator.snapshot().taskRunId());

        assertFalse(coordinator.shouldDeferUserBatch(List.of("turn-1")));
    }

    @Test
    void aQueuedRequestThatCannotBeInTheBatchIsReleasedInsteadOfFencingTheDriver() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        coordinator.openRoot("request-1", "turn-1");
        coordinator.requestQueued("turn-1");
        assertEquals(TaskRunState.AWAITING_ASYNC, coordinator.snapshot().state());

        assertFalse(coordinator.shouldDeferUserBatch(List.of("turn-2")));
        assertEquals(TaskRunState.REGISTERED, coordinator.snapshot().state());
        assertEquals(0, coordinator.snapshot().queuedRequests());
    }

    @Test
    void markingTheRequestQueuedAfterTheExecutionStartedMustNotFenceTheRun() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var run = coordinator.openRoot("request-1", "turn-1");
        // the driver can reach the message between the enqueue and this marking
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, List.of("turn-1"));
        coordinator.requestQueued("request-1", "turn-1");
        assertEquals(TaskRunState.RUNNING, coordinator.snapshot().state());
        assertEquals(0, coordinator.snapshot().queuedRequests());

        coordinator.cancelRun("user requested stop");
        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.CANCELLED, "user_cancelled");

        assertNull(coordinator.activeSnapshot(), "a cancelled run with no outstanding work must end");
        assertEquals(TaskRunOutcome.CANCELLED, coordinator.lastEndedSnapshot().outcome());
        assertEquals(run.taskRunId(), coordinator.lastEndedSnapshot().taskRunId());
        assertFalse(coordinator.shouldDeferUserBatch(List.of("turn-2")));
    }

    @Test
    void aFollowUpAcceptedAfterTheRootBatchStartedBecomesItsOwnQueuedRun() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        coordinator.openRoot("request-1", "turn-1");
        coordinator.executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, List.of("turn-1"));

        coordinator.requestQueued("request-2", "turn-2");

        var queued = coordinator.queuedSnapshots();
        assertEquals(1, queued.size());
        assertEquals(TaskRunState.QUEUED, queued.getFirst().state());
        assertEquals("request-2", queued.getFirst().requestId());

        coordinator.executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);
        assertNull(coordinator.activeSnapshot());
        assertFalse(coordinator.shouldDeferUserBatch(List.of("turn-2")));
        var claimed = coordinator.claimQueued(List.of("turn-2"));
        assertNotNull(claimed);
        assertEquals("request-2", claimed.requestId());
    }

    @Test
    void cancelRunReleasesAnAcceptedInputThatNeverStarted() {
        var coordinator = new TaskRunCoordinator("session-1", "inc-1");
        var run = coordinator.openRoot("request-1", "turn-1");
        coordinator.requestQueued("turn-1");

        var released = coordinator.cancelRun("user requested stop");

        assertEquals(List.of("turn-1"), released);
        assertNull(coordinator.activeSnapshot());
        assertEquals(TaskRunOutcome.CANCELLED, coordinator.lastEndedSnapshot().outcome());
        assertEquals(run.taskRunId(), coordinator.lastEndedSnapshot().taskRunId());
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
