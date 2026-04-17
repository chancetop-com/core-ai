package ai.core.server.sandbox.agentsandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.kubernetes.KubernetesClient;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class AgentSandboxProvider implements SandboxProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSandboxProvider.class);

    private final AgentSandboxClient client;
    private final SandboxConfig defaultConfig;
    private final KubernetesClient kubernetesClient;  // optional, for NodePort support
    private final boolean useHostPort;

    public AgentSandboxProvider(AgentSandboxClient client, SandboxConfig defaultConfig) {
        this(client, defaultConfig, null, false);
    }

    public AgentSandboxProvider(AgentSandboxClient client, SandboxConfig defaultConfig, KubernetesClient kubernetesClient, boolean useHostPort) {
        this.client = client;
        this.defaultConfig = defaultConfig != null ? defaultConfig : new SandboxConfig();
        this.kubernetesClient = kubernetesClient;
        this.useHostPort = useHostPort;
    }

    @Override
    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        effectiveConfig.validate();

        var specBuilder = new SandboxCRSpecBuilder(effectiveConfig, sessionId, userId);
        var crName = specBuilder.sandboxName();

        LOGGER.info("creating agent sandbox CR: name={}, sessionId={}", crName, sessionId);

        var crManifest = specBuilder.build();
        var crJson = JSON.toJSON(crManifest);
        client.createSandbox(crJson);

        // Wait for the SIG controller to provision the sandbox
        var cr = client.waitForReady(crName, 120_000);

        var timeoutSeconds = effectiveConfig.timeoutSeconds != null
                ? effectiveConfig.timeoutSeconds
                : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;

        // In hostPort mode, create a NodePort service for local dev access
        if (useHostPort && kubernetesClient != null) {
            return acquireWithNodePort(cr, crName, timeoutSeconds);
        }

        // Resolve connectivity: prefer serviceFQDN (cluster DNS), fallback to podIP
        String host;
        int port = SandboxConstants.RUNTIME_PORT;
        if (cr.status.serviceFQDN != null && !cr.status.serviceFQDN.isBlank()) {
            host = cr.status.serviceFQDN;
            LOGGER.info("agent sandbox using serviceFQDN: name={}, fqdn={}", crName, host);
        } else if (cr.status.podIPs != null && cr.status.podIPs.length > 0) {
            host = cr.status.podIPs[0];
            LOGGER.info("agent sandbox using podIP: name={}, ip={}", crName, host);
        } else {
            LOGGER.error("sandbox CR has no network info: name={}", crName);
            client.deleteSandbox(crName);
            throw new RuntimeException("Sandbox CR has no network connectivity info: " + crName);
        }

        LOGGER.info("agent sandbox ready: name={}, host={}, port={}", crName, host, port);
        var sandbox = new AgentSandbox(crName, null, host, port, timeoutSeconds);
        sandbox.waitForReady();
        return sandbox;
    }

    private Sandbox acquireWithNodePort(AgentSandboxClient.SandboxCR cr, String crName, int timeoutSeconds) {
        // Find the pod created by the controller using the sandbox's service selector
        var serviceName = "svc-" + crName;
        try {
            // The controller creates pods with label matching the sandbox name
            // Create a NodePort service targeting pods with the sandbox's selector
            var selectorLabel = cr.status.selector;
            if (selectorLabel == null || selectorLabel.isBlank()) {
                // Fallback: use the service name from status to find the pod
                selectorLabel = "agents.x-k8s.io/sandbox-name=" + crName;
            }
            var serviceInfo = kubernetesClient.createNodePortServiceBySelector(serviceName, selectorLabel, SandboxConstants.RUNTIME_PORT);
            var nodePort = serviceInfo.spec.ports[0].nodePort;
            LOGGER.info("created NodePort service for agent sandbox: {} -> localhost:{}", serviceName, nodePort);
            var sandbox = new AgentSandbox(crName, serviceName, "localhost", nodePort, timeoutSeconds);
            sandbox.waitForReady();
            return sandbox;
        } catch (Exception e) {
            LOGGER.error("failed to create NodePort service for agent sandbox: {}", crName, e);
            client.deleteSandbox(crName);
            throw new RuntimeException("Failed to create agent sandbox service", e);
        }
    }

    @Override
    public void release(Sandbox sandbox) {
        if (sandbox == null) return;
        var agentSandbox = (AgentSandbox) sandbox;
        var crName = sandbox.getId();
        LOGGER.info("releasing agent sandbox CR: name={}", crName);
        try {
            // Delete NodePort service if exists
            if (agentSandbox.serviceName() != null && kubernetesClient != null) {
                kubernetesClient.deleteService(agentSandbox.serviceName());
            }
            client.deleteSandbox(crName);
            sandbox.close();
        } catch (Exception e) {
            LOGGER.error("failed to release agent sandbox CR: name={}", crName, e);
            sandbox.close();
        }
    }

    @Override
    public SandboxStatus getStatus(Sandbox sandbox) {
        if (sandbox == null) return SandboxStatus.TERMINATED;
        try {
            var opt = client.getSandbox(sandbox.getId());
            if (opt.isEmpty()) return SandboxStatus.TERMINATED;
            var cr = opt.get();
            if (cr.status == null) return SandboxStatus.CREATING;
            // Check conditions
            if (cr.status.conditions != null) {
                for (var c : cr.status.conditions) {
                    if ("Ready".equals(c.type) && "True".equals(c.status)) {
                        return sandbox.getStatus() == SandboxStatus.EXECUTING ? SandboxStatus.EXECUTING : SandboxStatus.READY;
                    }
                    if ("Failed".equals(c.type) && "True".equals(c.status)) {
                        return SandboxStatus.ERROR;
                    }
                }
            }
            // If has podIPs, assume running
            if (cr.status.podIPs != null && cr.status.podIPs.length > 0) {
                return SandboxStatus.READY;
            }
            return SandboxStatus.CREATING;
        } catch (Exception e) {
            LOGGER.warn("failed to get agent sandbox status: name={}", sandbox.getId(), e);
            return SandboxStatus.ERROR;
        }
    }

    public void cleanupExpiredSandboxes(int maxLifetimeSeconds) {
        try {
            var now = Instant.now();
            var sandboxes = client.listSandboxes("core-ai/component=sandbox");
            for (var cr : sandboxes) {
                if (cr.metadata == null || cr.metadata.creationTimestamp == null) {
                    continue;
                }
                var created = ZonedDateTime.parse(cr.metadata.creationTimestamp).toInstant();
                var age = Duration.between(created, now);
                if (age.getSeconds() > maxLifetimeSeconds) {
                    LOGGER.info("deleting expired agent sandbox CR: name={}, age={}s", cr.getName(), age.getSeconds());
                    // Also cleanup NodePort service if exists
                    if (kubernetesClient != null) {
                        kubernetesClient.deleteService("svc-" + cr.getName());
                    }
                    client.deleteSandbox(cr.getName());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("failed to run agent sandbox cleanup", e);
        }
    }
}
