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
        bind(SandboxSnapshotService.class);
        schedule().fixedRate("sandbox-snapshot-cleanup", bind(SandboxSnapshotCleanupJob.class), Duration.ofHours(1));
    }
}
