package ai.core.server.sandbox.kubernetes;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.sandbox.SandboxConstants;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class KubernetesSandboxProvider implements SandboxProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesSandboxProvider.class);

    private final KubernetesClient kubernetesClient;
    private final SandboxConfig defaultConfig;

    public KubernetesSandboxProvider(String apiServer, String token, String namespace, SandboxConfig defaultConfig) {
        this.kubernetesClient = new KubernetesClient(apiServer, token, namespace, 60);
        this.defaultConfig = defaultConfig != null ? defaultConfig : new SandboxConfig();
    }

    @Override
    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        effectiveConfig.validate();

        var podBuilder = new KubernetesPodSpecBuilder(effectiveConfig, sessionId, userId);
        var podName = podBuilder.podName();

        LOGGER.info("creating sandbox pod: name={}, image={}, sessionId={}", podName, effectiveConfig.image, sessionId);

        var podManifest = podBuilder.build();
        var podJson = JSON.toJSON(podManifest);
        var podInfo = kubernetesClient.createPod(podJson);

        LOGGER.debug("pod created: name={}, uid={}", podInfo.getName(), podInfo.getUid());

        var readyPod = kubernetesClient.waitForReady(podInfo.getName());
        var podIp = readyPod.getIp();

        LOGGER.info("sandbox pod ready: name={}, ip={}", podName, podIp);

        if (podIp == null || podIp.isBlank()) {
            LOGGER.error("pod has no IP address: name={}", podName);
            cleanupPod(podName);
            return null;
        }

        var timeoutSeconds = effectiveConfig.timeoutSeconds != null
                ? effectiveConfig.timeoutSeconds
                : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;
        return new KubernetesSandbox(podName, podIp, timeoutSeconds);
    }

    private void cleanupPod(String podName) {
        try {
            kubernetesClient.deletePod(podName);
        } catch (Exception cleanupError) {
            LOGGER.warn("failed to cleanup pod after creation failure: name={}", podName, cleanupError);
        }
    }

    @Override
    public void release(Sandbox sandbox) {
        if (sandbox == null) return;

        var sandboxId = sandbox.getId();
        LOGGER.info("releasing sandbox pod: name={}", sandboxId);

        try {
            kubernetesClient.deletePod(sandboxId);
            sandbox.close();
            LOGGER.debug("sandbox pod released: name={}", sandboxId);
        } catch (Exception e) {
            LOGGER.error("failed to release sandbox pod: name={}", sandboxId, e);
            sandbox.close(); // Still close the sandbox even if delete fails
        }
    }

    @Override
    public SandboxStatus getStatus(Sandbox sandbox) {
        if (sandbox == null) return SandboxStatus.TERMINATED;

        try {
            var podOpt = kubernetesClient.getPod(sandbox.getId());
            if (podOpt.isEmpty()) {
                return SandboxStatus.TERMINATED;
            }
            var pod = podOpt.get();
            var phase = pod.getPhase();

            return switch (phase) {
                case "Pending" -> SandboxStatus.CREATING;
                case "Running" -> sandbox.getStatus() == SandboxStatus.EXECUTING
                        ? SandboxStatus.EXECUTING
                        : SandboxStatus.READY;
                case "Succeeded", "Failed" -> SandboxStatus.ERROR;
                case "Unknown" -> SandboxStatus.ERROR;
                default -> sandbox.getStatus();
            };
        } catch (Exception e) {
            LOGGER.warn("failed to get pod status: name={}", sandbox.getId(), e);
            return SandboxStatus.ERROR;
        }
    }
}
