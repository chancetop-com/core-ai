package ai.core.server.task;

import ai.core.api.server.task.BackgroundTaskView;
import ai.core.api.server.task.ListTasksRequest;
import ai.core.api.server.task.ListTasksResponse;
import ai.core.api.server.task.RetryTaskResponse;
import ai.core.api.server.task.RunTaskRequest;
import ai.core.api.server.task.RunTaskResponse;
import ai.core.api.server.task.TaskWebService;
import ai.core.server.domain.BackgroundTask;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.util.List;

/**
 * Admin API for background task status queries and retry.
 *
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.TASK_VIEW)
public class TaskWebServiceImpl implements TaskWebService {
    private static String extractType(String taskId) {
        int idx = taskId.lastIndexOf(':');
        if (idx <= 0) return taskId;
        return taskId.substring(0, idx);
    }

    @Inject
    TaskRunner taskRunner;

    @Override
    public ListTasksResponse list(ListTasksRequest request) {
        String type = request.type;
        int limit = request.limit == null ? 20 : request.limit;
        List<BackgroundTask> tasks = taskRunner.list(type, limit);
        var response = new ListTasksResponse();
        response.tasks = tasks.stream().map(this::toView).toList();
        return response;
    }

    @Override
    public RetryTaskResponse retry(String taskId) {
        String type = extractType(taskId);
        var task = taskRunner.getTask(type);
        if (task == null) throw new NotFoundException("unknown task type: " + type);
        boolean accepted = taskRunner.retry(task, taskId);
        if (!accepted) throw new BadRequestException("task not in FAILED state or not found: " + taskId);
        var response = new RetryTaskResponse();
        response.retryAccepted = Boolean.TRUE;
        response.taskId = taskId;
        return response;
    }

    @Override
    public RunTaskResponse run(RunTaskRequest request) {
        String type = request.type;
        String date = request.date;
        if (type == null || type.isBlank()) throw new BadRequestException("missing required field: type");
        if (date == null || date.isBlank()) throw new BadRequestException("missing required field: date");
        var task = taskRunner.getTask(type);
        if (task == null) throw new NotFoundException("unknown task type: " + type);
        String taskId = type + ":" + date;
        taskRunner.run(task, taskId);
        var response = new RunTaskResponse();
        response.taskAccepted = Boolean.TRUE;
        response.taskId = taskId;
        return response;
    }

    private BackgroundTaskView toView(BackgroundTask task) {
        var view = new BackgroundTaskView();
        view.id = task.id;
        view.type = task.type;
        view.status = task.status != null ? task.status.name() : null;
        view.statusText = task.statusText;
        view.claimedBy = task.claimedBy;
        view.startedAt = task.startedAt;
        view.completedAt = task.completedAt;
        view.retryCount = task.retryCount;
        view.logs = task.logs;
        view.taskState = task.taskState;
        return view;
    }
}
