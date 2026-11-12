package ai.core.server.schedule;

import ai.core.schedule.ScheduledTaskStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SessionScheduleStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            var store = bind(PgScheduledTaskStore.class);
            bind(SessionScheduleStore.class, store);
            bind(ScheduledTaskStore.class, store);
            var migration = bind(SessionScheduleMigrationService.class);
            onStartup(migration::migrate);
        } else {
            var store = bind(MongoScheduledTaskStore.class);
            bind(SessionScheduleStore.class, store);
            bind(ScheduledTaskStore.class, store);
        }
    }
}
