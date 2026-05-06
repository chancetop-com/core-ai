package ai.core.server.a2a;

import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import java.nio.charset.StandardCharsets;

/**
 * @author xander
 */
public class A2ATaskController {
    private static final ContentType A2A_JSON = ContentType.parse("application/a2a+json");
    private static final String CANCEL_SUFFIX = ":cancel";

    @Inject
    ServerA2AService a2aService;

    public Response get(Request request) {
        var params = new GetTaskRequest();
        params.id = cleanTaskId(request.pathParam("taskId"));
        return json(JsonUtil.toJson(a2aService.getTask(params)));
    }

    public Response cancel(Request request) {
        var params = new CancelTaskRequest();
        params.id = cleanTaskId(request.pathParam("taskId"));
        return json(JsonUtil.toJson(a2aService.cancelTask(params)));
    }

    private String cleanTaskId(String taskId) {
        if (taskId != null && taskId.endsWith(CANCEL_SUFFIX)) {
            return taskId.substring(0, taskId.length() - CANCEL_SUFFIX.length());
        }
        return taskId;
    }

    private Response json(String body) {
        return Response.bytes(body.getBytes(StandardCharsets.UTF_8)).contentType(A2A_JSON);
    }
}
