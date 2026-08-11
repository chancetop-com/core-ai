package ai.core.server;

import ai.core.server.sandbox.snapshot.SandboxSnapshotCleanupJob;
import ai.core.server.sandbox.snapshot.SandboxSnapshotPolicy;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import core.framework.module.Module;

import java.time.Duration;

/**
 * @author stephen
 */
public class SandboxSnapshotModule extends Module {
    @Override
    protected void initialize() {
        var deploymentAllowed = "true".equalsIgnoreCase(
                property("sys.sandbox.snapshot.enabled").orElse("false"));
        var policy = bind(SandboxSnapshotPolicy.class);
        policy.configure(deploymentAllowed);
        var service = bind(SandboxSnapshotService.class);
        service.configure(property("azure.blob.snapshot.container").orElse("sandbox-snapshots"),
                property("storage.minio.snapshot.bucket").orElse("sandbox-snapshots"));
        schedule().fixedRate("sandbox-snapshot-cleanup", bind(SandboxSnapshotCleanupJob.class), Duration.ofHours(1));
    }
}
