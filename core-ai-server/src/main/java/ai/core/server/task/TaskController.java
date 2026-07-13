package ai.core.server.task;

import ai.core.server.domain.BackgroundTask;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin API for background task status queries and retry.
 *
 * @author cyril
 */
public class TaskController {
    private static final ObjectMapper MAPPER = JsonUtil.OBJECT_MAPPER;

    @Inject
    TaskRunner taskRunner;

    /**
     * GET /api/admin/tasks?type=TRACE_DAILY_MAINTENANCE&limit=20
     */
    public Response list(Request request) {
        var params = request.queryParams();
        String type = params.get("type");
        int limit = parseInt(params.getOrDefault("limit", "20"), 20);

        List<BackgroundTask> tasks = taskRunner.list(type, limit);
        var result = tasks.stream().map(this::toMap).toList();
        return jsonResponse(Map.of("tasks", result));
    }

    /**
     * PUT /api/admin/tasks/{taskId}/retry
     */
    public Response retry(Request request) {
        String taskId = request.pathParam("taskId");
        String type = extractType(taskId);

        var task = taskRunner.getTask(type);
        if (task == null) {
            return Response.text("unknown task type: " + type).status(HTTPStatus.NOT_FOUND);
        }

        boolean accepted = taskRunner.retry(task, taskId);
        if (!accepted) {
            return Response.text("task not in FAILED state or not found: " + taskId)
                    .status(HTTPStatus.BAD_REQUEST);
        }

        return jsonResponse(Map.of("retry_accepted", true, "task_id", taskId));
    }

    /**
     * POST /api/admin/tasks
     * Body: { "type": "TRACE_DAILY_MAINTENANCE", "date": "2026-07-01" }
     */
    public Response run(Request request) {
        byte[] rawBody = request.body().orElse(null);
        if (rawBody == null || rawBody.length == 0) {
            return Response.text("missing request body").status(HTTPStatus.BAD_REQUEST);
        }
        Map<String, String> body;
        try {
            body = MAPPER.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            return Response.text("invalid request body: " + e.getMessage()).status(HTTPStatus.BAD_REQUEST);
        }

        String type = body.get("type");
        String date = body.get("date");
        if (type == null || type.isBlank()) {
            return Response.text("missing required field: type").status(HTTPStatus.BAD_REQUEST);
        }
        if (date == null || date.isBlank()) {
            return Response.text("missing required field: date").status(HTTPStatus.BAD_REQUEST);
        }

        var task = taskRunner.getTask(type);
        if (task == null) {
            return Response.text("unknown task type: " + type).status(HTTPStatus.NOT_FOUND);
        }

        String taskId = type + ":" + date;
        taskRunner.run(task, taskId);

        return jsonResponse(Map.of("task_accepted", true, "task_id", taskId));
    }

    private static String extractType(String taskId) {
        int idx = taskId.lastIndexOf(':');
        if (idx <= 0) return taskId;
        return taskId.substring(0, idx);
    }

    private Map<String, Object> toMap(BackgroundTask task) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", task.id);
        map.put("type", task.type);
        map.put("status", task.status != null ? task.status.name() : null);
        map.put("status_text", task.statusText);
        map.put("claimed_by", task.claimedBy);
        map.put("started_at", task.startedAt);
        map.put("completed_at", task.completedAt);
        map.put("retry_count", task.retryCount);
        map.put("logs", task.logs);
        map.put("task_state", task.taskState);
        return map;
    }

    private Response jsonResponse(Object data) {
        try {
            var json = MAPPER.writeValueAsBytes(data);
            return Response.bytes(json).contentType(ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            return Response.text("serialization error").status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static int parseInt(String s, int defaultVal) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
