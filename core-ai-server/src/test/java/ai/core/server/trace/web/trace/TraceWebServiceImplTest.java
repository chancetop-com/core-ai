package ai.core.server.trace.web.trace;

import ai.core.server.domain.User;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceService;
import ai.core.server.web.auth.AuthContext;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceWebServiceImplTest {
    @Test
    void publicWorkflowRunTraceIsReadableByOtherUser() {
        var service = service("viewer-1");
        var trace = trace("trace-1", "runner-1", "run-1");
        when(service.traceService.get("trace-1")).thenReturn(trace);
        when(service.workflowRunCollection.get("run-1")).thenReturn(Optional.of(run("runner-1", WorkflowVisibility.PUBLIC)));

        assertDoesNotThrow(() -> service.get("trace-1"));
    }

    @Test
    void privateWorkflowRunTraceStaysHiddenFromOtherUser() {
        var service = service("viewer-1");
        var trace = trace("trace-1", "runner-1", "run-1");
        when(service.traceService.get("trace-1")).thenReturn(trace);
        when(service.workflowRunCollection.get("run-1")).thenReturn(Optional.of(run("runner-1", WorkflowVisibility.PRIVATE)));

        assertThrows(NotFoundException.class, () -> service.get("trace-1"));
    }

    private TraceWebServiceImpl service(String userId) {
        var service = new TraceWebServiceImpl();
        service.traceService = mock(TraceService.class);
        service.userCollection = userCollection();
        service.workflowRunCollection = workflowRunCollection();
        service.webContext = mock(WebContext.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn(userId);
        when(service.userCollection.get(userId)).thenReturn(Optional.empty());
        return service;
    }

    private Trace trace(String traceId, String userId, String workflowRunId) {
        var trace = new Trace();
        trace.id = traceId;
        trace.traceId = traceId;
        trace.userId = userId;
        trace.metadata = Map.of("workflow_run_id", workflowRunId);
        return trace;
    }

    private WorkflowRun run(String userId, WorkflowVisibility visibility) {
        var run = new WorkflowRun();
        run.id = "run-1";
        run.userId = userId;
        run.visibility = visibility;
        return run;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<User> userCollection() {
        return (MongoCollection<User>) mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<WorkflowRun> workflowRunCollection() {
        return (MongoCollection<WorkflowRun>) mock(MongoCollection.class);
    }
}
