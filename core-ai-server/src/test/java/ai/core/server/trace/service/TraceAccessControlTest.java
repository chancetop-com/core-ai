package ai.core.server.trace.service;

import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import ai.core.server.trace.domain.Trace;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trace read authorization branches: visibility set, workflow run reader, deny otherwise.
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
    void unrestrictedVisibilityReadsAnyTrace() {
        assertTrue(control.canRead(trace("user-1", null), "user-2", TraceVisibility.all()));
    }

    @Test
    void ownerCanReadOwnTrace() {
        assertTrue(control.canRead(trace("user-1", null), "user-1", TraceVisibility.of(Set.of("user-1"))));
    }

    @Test
    void apiScopeReadsApiUserTrace() {
        var visibility = TraceVisibility.of(Set.of("developer-1", "api:acme"));
        assertTrue(control.canRead(trace("api:acme", null), "developer-1", visibility));
    }

    @Test
    void apiScopeDeniesOtherInternalUserTrace() {
        var visibility = TraceVisibility.of(Set.of("developer-1", "api:acme"));
        assertFalse(control.canRead(trace("internal-2", null), "developer-1", visibility));
    }

    @Test
    void nullUserDenied() {
        assertFalse(control.canRead(trace("user-1", null), null, TraceVisibility.of(Set.of("user-1"))));
    }

    @Test
    void traceWithoutWorkflowLinkDenied() {
        assertFalse(control.canRead(trace("user-1", null), "user-2", TraceVisibility.of(Set.of("user-2"))));
    }

    @Test
    void workflowRunReaderAllowed() {
        var trace = trace("user-1", "run-1");
        var run = new WorkflowRun();
        run.id = "run-1";
        run.userId = "user-2";
        run.visibility = WorkflowVisibility.PRIVATE;
        when(workflowRunCollection.get("run-1")).thenReturn(Optional.of(run));

        assertTrue(control.canRead(trace, "user-2", TraceVisibility.of(Set.of("user-2"))));
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

        assertFalse(control.canRead(trace, "user-3", TraceVisibility.of(Set.of("user-3"))));
    }

    @Test
    void missingWorkflowRunDeniedWithoutLookup() {
        var trace = trace("user-1", "run-1");
        when(workflowRunCollection.get("run-1")).thenReturn(Optional.empty());

        assertFalse(control.canRead(trace, "user-2", TraceVisibility.of(Set.of("user-2"))));
        verify(workflowRunCollection).get("run-1");
    }

    @Test
    void nullTraceDenied() {
        assertFalse(control.canRead(null, "user-1", TraceVisibility.all()));
        verify(workflowRunCollection, never()).get(any());
    }
}
