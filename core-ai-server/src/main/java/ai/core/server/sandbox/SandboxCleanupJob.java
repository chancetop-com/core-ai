package ai.core.server.sandbox;

import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.sandbox.agentsandbox.AgentSandboxProvider;
import ai.core.server.sandbox.kubernetes.KubernetesSandboxProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * @author stephen
 */
public class SandboxCleanupJob implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxCleanupJob.class);

    private final SandboxManager sandboxManager;
    private final SandboxProvider provider;
    private final Consumer<SandboxManager.SandboxEntry> beforeRelease;

    public SandboxCleanupJob(SandboxManager sandboxManager, SandboxProvider provider) {
        this(sandboxManager, provider, null);
    }

    public SandboxCleanupJob(SandboxManager sandboxManager, SandboxProvider provider,
                             Consumer<SandboxManager.SandboxEntry> beforeRelease) {
        this.sandboxManager = sandboxManager;
        this.provider = provider;
        this.beforeRelease = beforeRelease;
    }

    @Override
    public void run() {
        try {
            LOGGER.debug("running sandbox cleanup job");
            sandboxManager.cleanupExpired(beforeRelease);
            // Cleanup expired resources in K8s (handles orphans from crashed server instances)
            if (provider instanceof KubernetesSandboxProvider k8sProvider) {
                k8sProvider.cleanupExpiredPods(SandboxConstants.DEFAULT_TIMEOUT_SECONDS);
            } else if (provider instanceof AgentSandboxProvider agentProvider) {
                agentProvider.cleanupExpiredSandboxes(SandboxConstants.DEFAULT_TIMEOUT_SECONDS);
            }
        } catch (Exception e) {
            LOGGER.error("sandbox cleanup job failed", e);
        }
    }
}
