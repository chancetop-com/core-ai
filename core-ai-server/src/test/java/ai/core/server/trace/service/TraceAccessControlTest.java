package ai.core.server.trace.service;

import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import ai.core.server.trace.domain.Trace;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trace read authorization branches: admin, owner, workflow run reader, deny otherwise.
 *
 * @author stephen
 */
class TraceAccessControlTest {
    private static Trace trace(String userId, String workflowRunId) {
        var trace = new Trace();
        trace.traceId = "trace-1";
        trace.userId = userId;
        if (workflowRunId != null) trace.metadata = Map.of("workflow_run_id", workflowRunId);
        return trace;
    }

    @SuppressWarnings("unchecked")
    private final MongoCollection<WorkflowRun> workflowRunCollection = mock(MongoCollection.class);
    private final TraceAccessControl control;

    TraceAccessControlTest() {
        control = new TraceAccessControl();
        control.workflowRunCollection = workflowRunCollection;
    }

    @Test
    void adminCanReadAnyTrace() {
        assertTrue(control.canRead(trace("user-1", null), "user-2", true));
    }

    @Test
    void ownerCanReadOwnTrace() {
        assertTrue(control.canRead(trace("user-1", null), "user-1", false));
    }

    @Test
    void nullUserDenied() {
        assertFalse(control.canRead(trace("user-1", null), null, false));
    }

    @Test
    void traceWithoutWorkflowLinkDenied() {
        assertFalse(control.canRead(trace("user-1", null), "user-2", false));
    }

    @Test
    void workflowRunReaderAllowed() {
        var trace = trace("user-1", "run-1");
        var run = new WorkflowRun();
        run.id = "run-1";
        run.userId = "user-2";
        run.visibility = WorkflowVisibility.PRIVATE;
        when(workflowRunCollection.get("run-1")).thenReturn(Optional.of(run));

        assertTrue(control.canRead(trace, "user-2", false));
        verify(workflowRunCollection).get("run-1");
    }

    @Test
    void workflowRunNonReaderDenied() {
        var trace = trace("user-1", "run-1");
        var run = new WorkflowRun();
        run.id = "run-1";
        run.userId = "user-2";
        run.visibility = WorkflowVisibility.PRIVATE;
        when(workflowRunCollection.get("run-1")).thenReturn(Optional.of(run));

        assertFalse(control.canRead(trace, "user-3", false));
    }

    @Test
    void missingWorkflowRunDeniedWithoutLookup() {
        var trace = trace("user-1", "run-1");
        when(workflowRunCollection.get("run-1")).thenReturn(Optional.empty());

        assertFalse(control.canRead(trace, "user-2", false));
        verify(workflowRunCollection).get("run-1");
    }

    @Test
    void nullTraceDenied() {
        assertFalse(control.canRead(null, "user-1", true));
        verify(workflowRunCollection, never()).get(any());
    }
}
