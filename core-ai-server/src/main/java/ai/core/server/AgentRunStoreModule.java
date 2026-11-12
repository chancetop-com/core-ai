package ai.core.server;

import ai.core.server.run.AgentRunMigrationService;
import ai.core.server.run.AgentRunStore;
import ai.core.server.run.MongoAgentRunStore;
import ai.core.server.run.PgAgentRunStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AgentRunStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(AgentRunStore.class, bind(PgAgentRunStore.class));
            var migration = bind(AgentRunMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(AgentRunStore.class, bind(MongoAgentRunStore.class));
        }
    }
}
