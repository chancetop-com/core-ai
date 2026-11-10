package ai.core.server;

import ai.core.server.file.FileRecordMigrationService;
import ai.core.server.file.FileRecordStore;
import ai.core.server.file.MongoFileRecordStore;
import ai.core.server.file.PgFileRecordStore;
import core.framework.module.Module;

/**
 * Binds file metadata before ProjectAttributionModule, which injects it into ProjectAttributionStore.
 *
 * @author stephen
 */
public class FileStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(FileRecordStore.class, bind(PgFileRecordStore.class));
            var migration = bind(FileRecordMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(FileRecordStore.class, bind(MongoFileRecordStore.class));
        }
    }
}
