package ai.core.server;

import ai.core.server.memory.AgentMemoryMigrationService;
import ai.core.server.memory.AgentMemoryStore;
import ai.core.server.memory.MongoAgentMemoryStore;
import ai.core.server.memory.PgAgentMemoryStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AgentMemoryStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(AgentMemoryStore.class, bind(PgAgentMemoryStore.class));
            var migration = bind(AgentMemoryMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(AgentMemoryStore.class, bind(MongoAgentMemoryStore.class));
        }
    }
}
