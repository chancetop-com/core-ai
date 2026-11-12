package ai.core.server;

import ai.core.server.notification.MongoNotificationStore;
import ai.core.server.notification.NotificationMigrationService;
import ai.core.server.notification.NotificationStore;
import ai.core.server.notification.PgNotificationStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class NotificationStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(NotificationStore.class, bind(PgNotificationStore.class));
            var migration = bind(NotificationMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(NotificationStore.class, bind(MongoNotificationStore.class));
        }
    }
}
