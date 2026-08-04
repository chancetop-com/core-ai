package ai.core.server;

import ai.core.server.sandbox.snapshot.SandboxSnapshotCleanupJob;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class SandboxSnapshotModule extends Module {
    @Override
    protected void initialize() {
        var service = bind(SandboxSnapshotService.class);
        service.configure(property("azure.blob.snapshot.container").orElse("sandbox-snapshots"),
                property("storage.minio.snapshot.bucket").orElse("sandbox-snapshots"),
                "true".equalsIgnoreCase(property("sys.sandbox.snapshot.enabled").orElse("false")));
        schedule().fixedRate("sandbox-snapshot-cleanup", bind(SandboxSnapshotCleanupJob.class), Duration.ofHours(1));
    }
}
