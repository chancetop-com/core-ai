package ai.core.server.sandbox.snapshot;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.settings.SystemSettingsService;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SandboxSnapshotPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxSnapshotPolicy.class);

    @Inject
    SystemSettingsService settings;
    @Inject
    ObjectStorageServiceResolver storageResolver;

    private volatile boolean deploymentAllowed;
    private volatile Status lastLoggedStatus;

    public void configure(boolean deploymentAllowed) {
        this.deploymentAllowed = deploymentAllowed;
        LOGGER.info("sandbox snapshot deployment gate configured: allowed={}", deploymentAllowed);
    }

    public Status status() {
        return decision().status();
    }

    public Decision decision() {
        boolean requested = false;
        try {
            requested = settings.sandboxSnapshotEnabled();
        } catch (RuntimeException e) {
            LOGGER.warn("sandbox snapshot requested-state read failed", e);
        }
        ObjectStorageService storage = null;
        try {
            storage = storageResolver.resolve();
        } catch (RuntimeException e) {
            LOGGER.warn("sandbox snapshot storage resolution failed", e);
        }
        var configured = deploymentAllowed;
        var storageReady = storage != null;
        var status = new Status(requested, configured, storageReady,
                requested && configured && storageReady);
        logChange(status);
        return new Decision(status, storage);
    }

    private void logChange(Status status) {
        synchronized (this) {
            if (status.equals(lastLoggedStatus)) return;
            LOGGER.info("sandbox snapshot policy: requested={}, deploymentAllowed={}, storageReady={}, effective={}",
                    status.requestedEnabled(), status.deploymentAllowed(), status.storageReady(), status.effective());
            lastLoggedStatus = status;
        }
    }

    public record Status(boolean requestedEnabled, boolean deploymentAllowed,
                         boolean storageReady, boolean effective) {
    }

    public record Decision(Status status, ObjectStorageService storage) {
    }
}
