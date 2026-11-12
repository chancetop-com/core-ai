package ai.core.server;

import ai.core.server.hub.HubCallCleanupJob;
import ai.core.server.hub.HubCallMigrationService;
import ai.core.server.hub.HubCallStore;
import ai.core.server.hub.MongoHubCallStore;
import ai.core.server.hub.PgHubCallStore;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class HubCallStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(HubCallStore.class, bind(PgHubCallStore.class));
            var migration = bind(HubCallMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(HubCallStore.class, bind(MongoHubCallStore.class));
        }
        schedule().fixedRate("hub-call-cleanup", bind(HubCallCleanupJob.class), Duration.ofHours(24));
    }
}
