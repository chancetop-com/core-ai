package ai.core.server;

import ai.core.server.asynctask.AsyncToolTaskPollJob;
import ai.core.server.asynctask.AsyncToolTaskService;
import ai.core.server.asynctask.MongoAsyncTaskPersistence;
import core.framework.module.Module;

import java.time.Duration;

/**
 * Long-running tool calls (pending/poll): persisted registry, server-side refresh, session notification.
 * Loads after ToolRegistryModule (tool catalogue for poll dispatch) and before SessionModule, whose
 * context builders attach the manager to every execution context.
 *
 * @author stephen
 */
public class AsyncToolTaskModule extends Module {
    @Override
    protected void initialize() {
        var pollSeconds = property("sys.asyncTask.pollSeconds").map(Integer::parseInt).orElse(10);
        bind(MongoAsyncTaskPersistence.class);
        bind(AsyncToolTaskService.class);
        schedule().fixedRate("async-tool-task-poll", bind(AsyncToolTaskPollJob.class), Duration.ofSeconds(pollSeconds));
    }
}
