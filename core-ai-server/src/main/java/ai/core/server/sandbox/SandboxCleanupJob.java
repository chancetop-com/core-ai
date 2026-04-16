package ai.core.server.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class SandboxCleanupJob implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxCleanupJob.class);

    private final SandboxManager sandboxManager;

    public SandboxCleanupJob(SandboxManager sandboxManager) {
        this.sandboxManager = sandboxManager;
    }

    @Override
    public void run() {
        try {
            LOGGER.debug("running sandbox cleanup job");
            sandboxManager.cleanupExpired();
        } catch (Exception e) {
            LOGGER.error("sandbox cleanup job failed", e);
        }
    }
}
