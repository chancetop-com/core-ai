package ai.core.server;

import ai.core.server.sandbox.snapshot.MongoSandboxEpochStore;
import ai.core.server.sandbox.snapshot.MongoSandboxSnapshotStore;
import ai.core.server.sandbox.snapshot.PgSandboxEpochStore;
import ai.core.server.sandbox.snapshot.PgSandboxSnapshotStore;
import ai.core.server.sandbox.snapshot.SandboxEpochStore;
import ai.core.server.sandbox.snapshot.SandboxSnapshotMigrationService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SandboxSnapshotStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SandboxSnapshotStore.class, bind(PgSandboxSnapshotStore.class));
            bind(SandboxEpochStore.class, bind(PgSandboxEpochStore.class));
            var migration = bind(SandboxSnapshotMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(SandboxSnapshotStore.class, bind(MongoSandboxSnapshotStore.class));
            bind(SandboxEpochStore.class, bind(MongoSandboxEpochStore.class));
        }
    }
}
