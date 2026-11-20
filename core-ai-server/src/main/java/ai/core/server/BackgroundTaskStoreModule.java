package ai.core.server;

import ai.core.server.task.BackgroundTaskStore;
import ai.core.server.task.MongoBackgroundTaskStore;
import ai.core.server.task.PgBackgroundTaskStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class BackgroundTaskStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(BackgroundTaskStore.class, bind(PgBackgroundTaskStore.class));
        } else {
            bind(BackgroundTaskStore.class, bind(MongoBackgroundTaskStore.class));
        }
    }
}
