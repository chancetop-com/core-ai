package ai.core.server.sandbox.kubernetes;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.sandbox.SandboxConstants;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import ai.core.server.sandbox.kubernetes.KubernetesClient.PodInfo;

/**
 * @author stephen
 */
public class KubernetesSandboxProvider implements SandboxProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesSandboxProvider.class);

    private final KubernetesClient kubernetesClient;
    private final SandboxConfig defaultConfig;
    private final boolean useHostPort;

    // ...existing constructors...
    public KubernetesSandboxProvider(String apiServer, String token, String namespace, SandboxConfig defaultConfig) {
        this(new KubernetesClient(apiServer, token, namespace, 60), defaultConfig, false);
    }

    public KubernetesSandboxProvider(KubernetesClient kubernetesClient, SandboxConfig defaultConfig) {
        this(kubernetesClient, defaultConfig, false);
    }

    public KubernetesSandboxProvider(KubernetesClient kubernetesClient, SandboxConfig defaultConfig, boolean useHostPort) {
        this.kubernetesClient = kubernetesClient;
        this.defaultConfig = defaultConfig != null ? defaultConfig : new SandboxConfig();
        this.useHostPort = useHostPort;
    }

    public void cleanupExpiredPods(int maxLifetimeSeconds) {
        try {
            var now = Instant.now();
            var pods = kubernetesClient.listPods("component=sandbox");
            for (var pod : pods) {
                cleanupPodIfExpired(pod, now, maxLifetimeSeconds);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to run sandbox pod cleanup", e);
        }
    }

    private void cleanupPodIfExpired(PodInfo pod, Instant now, int maxLifetimeSeconds) {
        try {
            var created = ZonedDateTime.parse(pod.metadata.creationTimestamp).toInstant();
            var age = Duration.between(created, now);
            if (age.getSeconds() <= maxLifetimeSeconds) return;

            LOGGER.info("deleting expired sandbox pod: name={}, age={}s", pod.getName(), age.getSeconds());
            deleteSandboxResources(pod.getName());
        } catch (Exception e) {
            LOGGER.warn("failed to cleanup sandbox pod: {}", pod.getName(), e);
        }
    }

    private void deleteSandboxResources(String podName) {
        var serviceName = "svc-" + podName;
        try {
            kubernetesClient.deleteService(serviceName);
        } catch (Exception ignored) {
            // service may not exist
        }
        kubernetesClient.deletePod(podName);
    }

    @Override
    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        effectiveConfig.validate();

        var podBuilder = new KubernetesPodSpecBuilder(effectiveConfig, sessionId, userId, useHostPort);
        var podName = podBuilder.podName();

        LOGGER.info("creating sandbox pod: name={}, image={}, sessionId={}, useHostPort={}", podName, effectiveConfig.image, sessionId, useHostPort);

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

        // In hostPort mode, create a NodePort service and connect via localhost:nodePort
        if (useHostPort) {
            var serviceName = "svc-" + podName;
            try {
                var serviceInfo = kubernetesClient.createNodePortService(serviceName, podName, SandboxConstants.RUNTIME_PORT);
                var nodePort = serviceInfo.spec.ports[0].nodePort;
                LOGGER.info("created NodePort service: {} -> localhost:{} for pod {}", serviceName, nodePort, podName);
                var sandbox = new KubernetesSandbox(podName, serviceName, "localhost", nodePort, timeoutSeconds);
                sandbox.waitForReady();
                return sandbox;
            } catch (Exception e) {
                LOGGER.error("failed to create NodePort service for pod: {}", podName, e);
                cleanupPod(podName);
                throw new RuntimeException("Failed to create sandbox service", e);
            }
        }
        var sandbox = new KubernetesSandbox(podName, podIp, timeoutSeconds);
        sandbox.waitForReady();
        return sandbox;
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
            // Delete associated NodePort service if exists
            if (sandbox instanceof KubernetesSandbox k8sSandbox && k8sSandbox.getServiceName() != null) {
                kubernetesClient.deleteService(k8sSandbox.getServiceName());
                LOGGER.debug("deleted sandbox service: {}", k8sSandbox.getServiceName());
            }
            kubernetesClient.deletePod(sandboxId);
            sandbox.close();
            LOGGER.debug("sandbox pod released: name={}", sandboxId);
        } catch (Exception e) {
            LOGGER.error("failed to release sandbox pod: name={}", sandboxId, e);
            sandbox.close();
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
                case "Succeeded", "Failed", "Unknown" -> SandboxStatus.ERROR;
                default -> sandbox.getStatus();
            };
        } catch (Exception e) {
            LOGGER.warn("failed to get pod status: name={}", sandbox.getId(), e);
            return SandboxStatus.ERROR;
        }
    }
}
