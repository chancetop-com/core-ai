package ai.core.server;

import ai.core.server.quality.AgentQualityStore;
import ai.core.server.quality.MongoAgentQualityStore;
import ai.core.server.quality.PgAgentQualityStore;
import core.framework.module.Module;

/**
 * Quality policy and run storage follows the same switch as the agent store, so publishing cannot fall
 * back to a store that does not exist in the current deployment.
 *
 * @author core-ai
 */
public class AgentQualityStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(AgentQualityStore.class, bind(PgAgentQualityStore.class));
        } else {
            bind(AgentQualityStore.class, bind(MongoAgentQualityStore.class));
        }
    }
}
