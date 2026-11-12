package ai.core.server.schedule;

import core.framework.module.Module;

/**
 * @author stephen
 */
public class AgentScheduleStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(AgentScheduleStore.class, bind(PgAgentScheduleStore.class));
            var migration = bind(AgentScheduleMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(AgentScheduleStore.class, bind(MongoAgentScheduleStore.class));
        }
    }
}
