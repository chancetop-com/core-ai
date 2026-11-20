package ai.core.server.comparison;

import core.framework.module.Module;

/**
 * @author stephen
 */
public class AgentVersionStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(AgentVersionStore.class, bind(PgAgentVersionStore.class));
        } else {
            bind(AgentVersionStore.class, bind(MongoAgentVersionStore.class));
        }
    }
}
