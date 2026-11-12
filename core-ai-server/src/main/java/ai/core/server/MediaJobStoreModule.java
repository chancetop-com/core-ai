package ai.core.server;

import ai.core.server.gateway.MediaJobMigrationService;
import ai.core.server.gateway.MediaJobStore;
import ai.core.server.gateway.MongoMediaJobStore;
import ai.core.server.gateway.PgMediaJobStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class MediaJobStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(MediaJobStore.class, bind(PgMediaJobStore.class));
            var migration = bind(MediaJobMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(MediaJobStore.class, bind(MongoMediaJobStore.class));
        }
    }
}
