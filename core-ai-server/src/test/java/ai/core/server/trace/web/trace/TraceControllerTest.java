package ai.core.server.trace.web.trace;

import ai.core.server.domain.User;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceService;
import ai.core.server.web.auth.AuthContext;
import core.framework.api.http.HTTPStatus;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.WebContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceControllerTest {
    @Test
    void publicWorkflowRunTraceIsReadableByOtherUser() {
        var controller = controller("viewer-1");
        var trace = trace("trace-1", "runner-1", "run-1");
        when(controller.traceService.get("trace-1")).thenReturn(trace);
        when(controller.workflowRunCollection.get("run-1")).thenReturn(Optional.of(run("runner-1", WorkflowVisibility.PUBLIC)));

        var response = controller.get(request("trace-1"));

        assertEquals(HTTPStatus.OK, response.status());
    }

    @Test
    void privateWorkflowRunTraceStaysHiddenFromOtherUser() {
        var controller = controller("viewer-1");
        var trace = trace("trace-1", "runner-1", "run-1");
        when(controller.traceService.get("trace-1")).thenReturn(trace);
        when(controller.workflowRunCollection.get("run-1")).thenReturn(Optional.of(run("runner-1", WorkflowVisibility.PRIVATE)));

        var response = controller.get(request("trace-1"));

        assertEquals(HTTPStatus.NOT_FOUND, response.status());
    }

    private TraceController controller(String userId) {
        var controller = new TraceController();
        controller.traceService = mock(TraceService.class);
        controller.userCollection = userCollection();
        controller.workflowRunCollection = workflowRunCollection();
        controller.webContext = mock(WebContext.class);
        when(controller.webContext.get(AuthContext.USER_ID_KEY)).thenReturn(userId);
        when(controller.userCollection.get(userId)).thenReturn(Optional.empty());
        return controller;
    }

    private Request request(String traceId) {
        var request = mock(Request.class);
        when(request.pathParam("traceId")).thenReturn(traceId);
        return request;
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
