package ai.core.server;

import ai.core.server.task.TaskController;
import ai.core.server.task.TaskRunner;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class TaskModule extends Module {
    @Override
    protected void initialize() {
        bind(TaskRunner.class);
        var taskController = bind(TaskController.class);
        http().route(HTTPMethod.GET, "/api/admin/tasks", taskController::list);
        http().route(HTTPMethod.PUT, "/api/admin/tasks/:taskId/retry", taskController::retry);
        http().route(HTTPMethod.POST, "/api/admin/tasks", taskController::run);
    }
}
