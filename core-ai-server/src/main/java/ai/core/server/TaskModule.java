package ai.core.server;

import ai.core.api.server.task.TaskWebService;
import ai.core.server.task.TaskRunner;
import ai.core.server.task.TaskWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class TaskModule extends Module {
    @Override
    protected void initialize() {
        bind(TaskRunner.class);
        api().service(TaskWebService.class, bind(TaskWebServiceImpl.class));
    }
}
