package ai.core.server;

import ai.core.server.replay.MongoReplayExperimentStore;
import ai.core.server.replay.MongoReplayRunStore;
import ai.core.server.replay.PgReplayExperimentStore;
import ai.core.server.replay.PgReplayRunStore;
import ai.core.server.replay.ReplayExperimentStore;
import ai.core.server.replay.ReplayMigrationService;
import ai.core.server.replay.ReplayRunStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ReplayStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(ReplayExperimentStore.class, bind(PgReplayExperimentStore.class));
            bind(ReplayRunStore.class, bind(PgReplayRunStore.class));
            var migration = bind(ReplayMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(ReplayExperimentStore.class, bind(MongoReplayExperimentStore.class));
            bind(ReplayRunStore.class, bind(MongoReplayRunStore.class));
        }
    }
}
