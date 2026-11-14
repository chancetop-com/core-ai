package ai.core.server;

import ai.core.server.memory.AgentMemoryMigrationService;
import ai.core.server.memory.AgentMemoryStore;
import ai.core.server.memory.MongoAgentMemoryStore;
import ai.core.server.memory.PgAgentMemoryStore;
import ai.core.server.memory.experiment.AgentMemoryExperimentConfigMigrationService;
import ai.core.server.memory.experiment.AgentMemoryExperimentRunMigrationService;
import ai.core.server.memory.experiment.AgentMemoryExperimentStore;
import ai.core.server.memory.experiment.MongoAgentMemoryExperimentStore;
import ai.core.server.memory.experiment.PgAgentMemoryExperimentStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AgentMemoryStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(AgentMemoryStore.class, bind(PgAgentMemoryStore.class));
            bind(AgentMemoryExperimentStore.class, bind(PgAgentMemoryExperimentStore.class));
            var migration = bind(AgentMemoryMigrationService.class);
            var configMigration = bind(AgentMemoryExperimentConfigMigrationService.class);
            var runMigration = bind(AgentMemoryExperimentRunMigrationService.class);
            onStartup(() -> {
                migration.migrate();
                configMigration.start(true);
                runMigration.start(true);
            });
        } else {
            bind(AgentMemoryStore.class, bind(MongoAgentMemoryStore.class));
            bind(AgentMemoryExperimentStore.class, bind(MongoAgentMemoryExperimentStore.class));
        }
    }
}
