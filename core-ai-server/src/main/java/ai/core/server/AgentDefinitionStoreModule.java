package ai.core.server;

import ai.core.server.agent.AgentDefinitionMigrationService;
import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.agent.MongoAgentDefinitionStore;
import ai.core.server.agent.PgAgentDefinitionStore;
import core.framework.module.Module;

/**
 * Binds the agent definition store before ProjectAttributionModule and SessionModule, both of which
 * resolve agent definitions during startup/configuration.
 *
 * @author stephen
 */
public class AgentDefinitionStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(AgentDefinitionStore.class, bind(PgAgentDefinitionStore.class));
            var migration = bind(AgentDefinitionMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(AgentDefinitionStore.class, bind(MongoAgentDefinitionStore.class));
        }
    }
}
