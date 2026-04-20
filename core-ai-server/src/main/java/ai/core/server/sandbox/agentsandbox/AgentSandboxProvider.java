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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentSandboxProvider implements SandboxProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSandboxProvider.class);

    private final AgentSandboxClient client;
    private final AgentSandboxExtensionsClient extensionsClient;
    private final SandboxConfig defaultConfig;
    private final KubernetesClient kubernetesClient;
    private final boolean useHostPort;
    private final String templateName;
    private final String warmPoolName;

    public AgentSandboxProvider(AgentSandboxProviderConfig config) {
        this.client = config.client;
        this.extensionsClient = config.extensionsClient;
        this.defaultConfig = config.defaultConfig != null ? config.defaultConfig : new SandboxConfig();
        this.kubernetesClient = config.kubernetesClient;
        this.useHostPort = config.useHostPort;
        this.templateName = config.templateName;
        this.warmPoolName = config.warmPoolName != null ? config.warmPoolName : "default";
    }

    private boolean useWarmPool() {
        return templateName != null && !templateName.isBlank() && extensionsClient != null;
    }

    @Override
    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        if (useWarmPool()) {
            return acquireFromWarmPool(config, sessionId, userId);
        }
        return acquireDirect(config, sessionId, userId);
    }

    // --- Warm pool mode: create SandboxClaim ---

    private Sandbox acquireFromWarmPool(SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        effectiveConfig.validate();

        var claimName = "claim-" + UUID.randomUUID().toString().substring(0, 8);
        LOGGER.info("creating SandboxClaim from warm pool: name={}, template={}, pool={}, sessionId={}",
                claimName, templateName, warmPoolName, sessionId);

        var claimManifest = buildClaimManifest(claimName, effectiveConfig, sessionId, userId);
        var claimJson = JSON.toJSON(claimManifest);
        extensionsClient.createClaim(claimJson);

        // Warm pool should assign a pre-warmed sandbox almost instantly
        var claim = extensionsClient.waitForReady(claimName, 60_000);

        var timeoutSeconds = effectiveConfig.timeoutSeconds != null
                ? effectiveConfig.timeoutSeconds
                : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;

        // Resolve connectivity from claim status
        String host;
        int port = SandboxConstants.RUNTIME_PORT;
        if (claim.status.sandbox != null && claim.status.sandbox.podIPs != null && claim.status.sandbox.podIPs.length > 0) {
            host = claim.status.sandbox.podIPs[0];
            LOGGER.info("sandbox claim assigned: claim={}, sandbox={}, podIP={}",
                    claimName, claim.status.sandbox.name, host);
        } else {
            LOGGER.error("sandbox claim has no pod IP: claim={}", claimName);
            extensionsClient.deleteClaim(claimName);
            throw new RuntimeException("SandboxClaim has no pod IP: " + claimName);
        }

        // For local dev, create NodePort service
        if (useHostPort && kubernetesClient != null) {
            return acquireClaimWithNodePort(claim, claimName, timeoutSeconds);
        }

        var sandbox = new AgentSandbox(claimName, null, host, port, timeoutSeconds);
        sandbox.waitForReady();
        return sandbox;
    }

    private Sandbox acquireClaimWithNodePort(AgentSandboxExtensionsClient.SandboxClaim claim, String claimName, int timeoutSeconds) {
        var serviceName = "svc-" + claimName;
        try {
            // The assigned sandbox name can be used to find the pod
            var sandboxName = claim.status.sandbox.name;
            // Query the actual Sandbox CR to get its selector
            var sandboxCR = client.getSandbox(sandboxName);
            String selector;
            if (sandboxCR.isPresent() && sandboxCR.get().status != null && sandboxCR.get().status.selector != null) {
                selector = sandboxCR.get().status.selector;
            } else {
                var selectorLabel = "agents.x-k8s.io/sandbox-name-hash";
                selector = selectorLabel + "=" + sandboxName;
            }
            var serviceInfo = kubernetesClient.createNodePortServiceBySelector(serviceName, selector, SandboxConstants.RUNTIME_PORT);
            var nodePort = serviceInfo.spec.ports[0].nodePort;
            LOGGER.info("created NodePort service for sandbox claim: {} -> localhost:{}", serviceName, nodePort);
            var sandbox = new AgentSandbox(claimName, serviceName, "localhost", nodePort, timeoutSeconds);
            sandbox.waitForReady();
            return sandbox;
        } catch (Exception e) {
            LOGGER.error("failed to create NodePort service for sandbox claim: {}", claimName, e);
            extensionsClient.deleteClaim(claimName);
            throw new RuntimeException("Failed to create sandbox claim service", e);
        }
    }

    private Map<String, Object> buildClaimManifest(String claimName, SandboxConfig config, String sessionId, String userId) {
        var cr = new LinkedHashMap<String, Object>();
        cr.put("apiVersion", "extensions.agents.x-k8s.io/v1alpha1");
        cr.put("kind", "SandboxClaim");

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("name", claimName);
        metadata.put("labels", Map.of(
                "app.kubernetes.io/managed-by", "core-ai",
                "core-ai/component", "sandbox",
                "core-ai/session-id", sanitizeLabel(sessionId != null ? sessionId : "unknown"),
                "core-ai/user-id", sanitizeLabel(userId != null ? userId : "unknown")
        ));
        cr.put("metadata", metadata);

        var spec = new LinkedHashMap<String, Object>();
        spec.put("sandboxTemplateRef", Map.of("name", templateName));
        spec.put("warmpool", warmPoolName);

        var timeout = config.timeoutSeconds != null ? config.timeoutSeconds : 3600;
        var lifecycle = new LinkedHashMap<String, Object>();
        lifecycle.put("shutdownPolicy", "Delete");
        lifecycle.put("shutdownTime", Instant.now().plus(timeout, ChronoUnit.SECONDS).toString());
        spec.put("lifecycle", lifecycle);

        cr.put("spec", spec);
        return cr;
    }

    // --- Direct mode: create Sandbox CR ---

    private Sandbox acquireDirect(SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        effectiveConfig.validate();

        var specBuilder = new SandboxCRSpecBuilder(effectiveConfig, sessionId, userId);
        var crName = specBuilder.sandboxName();

        LOGGER.info("creating agent sandbox CR: name={}, sessionId={}", crName, sessionId);

        var crManifest = specBuilder.build();
        var crJson = JSON.toJSON(crManifest);
        client.createSandbox(crJson);

        var cr = client.waitForReady(crName, 120_000);

        var timeoutSeconds = effectiveConfig.timeoutSeconds != null
                ? effectiveConfig.timeoutSeconds
                : SandboxConstants.DEFAULT_TIMEOUT_SECONDS;

        if (useHostPort && kubernetesClient != null) {
            return acquireWithNodePort(cr, crName, timeoutSeconds);
        }

        String host;
        int port = SandboxConstants.RUNTIME_PORT;
        if (cr.status.serviceFQDN != null && !cr.status.serviceFQDN.isBlank()) {
            host = cr.status.serviceFQDN;
        } else if (cr.status.podIPs != null && cr.status.podIPs.length > 0) {
            host = cr.status.podIPs[0];
        } else {
            client.deleteSandbox(crName);
            throw new RuntimeException("Sandbox CR has no network connectivity info: " + crName);
        }

        LOGGER.info("agent sandbox ready: name={}, host={}, port={}", crName, host, port);
        var sandbox = new AgentSandbox(crName, null, host, port, timeoutSeconds);
        sandbox.waitForReady();
        return sandbox;
    }

    private Sandbox acquireWithNodePort(AgentSandboxClient.SandboxCR cr, String crName, int timeoutSeconds) {
        var serviceName = "svc-" + crName;
        try {
            var selectorLabel = cr.status.selector;
            if (selectorLabel == null || selectorLabel.isBlank()) {
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

    // --- Release & Status ---

    @Override
    public void release(Sandbox sandbox) {
        if (sandbox == null) return;
        var agentSandbox = (AgentSandbox) sandbox;
        var id = sandbox.getId();
        LOGGER.info("releasing agent sandbox: name={}", id);
        try {
            if (agentSandbox.serviceName() != null && kubernetesClient != null) {
                kubernetesClient.deleteService(agentSandbox.serviceName());
            }
            if (useWarmPool()) {
                extensionsClient.deleteClaim(id);
            } else {
                client.deleteSandbox(id);
            }
            sandbox.close();
        } catch (Exception e) {
            LOGGER.error("failed to release agent sandbox: name={}", id, e);
            sandbox.close();
        }
    }

    @Override
    public SandboxStatus getStatus(Sandbox sandbox) {
        if (sandbox == null) return SandboxStatus.TERMINATED;
        try {
            if (useWarmPool()) {
                return getClaimStatus(sandbox);
            }
            return getDirectStatus(sandbox);
        } catch (Exception e) {
            LOGGER.warn("failed to get agent sandbox status: name={}", sandbox.getId(), e);
            return SandboxStatus.ERROR;
        }
    }

    private SandboxStatus getClaimStatus(Sandbox sandbox) {
        var opt = extensionsClient.getClaim(sandbox.getId());
        if (opt.isEmpty()) return SandboxStatus.TERMINATED;
        var claim = opt.get();
        if (claim.status == null) return SandboxStatus.CREATING;
        if (claim.status.conditions != null) {
            for (var c : claim.status.conditions) {
                if ("Ready".equals(c.type) && "True".equals(c.status)) {
                    return sandbox.getStatus() == SandboxStatus.EXECUTING ? SandboxStatus.EXECUTING : SandboxStatus.READY;
                }
                if ("Failed".equals(c.type) && "True".equals(c.status)) {
                    return SandboxStatus.ERROR;
                }
            }
        }
        if (claim.status.sandbox != null && claim.status.sandbox.podIPs != null && claim.status.sandbox.podIPs.length > 0) {
            return SandboxStatus.READY;
        }
        return SandboxStatus.CREATING;
    }

    private SandboxStatus getDirectStatus(Sandbox sandbox) {
        var opt = client.getSandbox(sandbox.getId());
        if (opt.isEmpty()) return SandboxStatus.TERMINATED;
        var cr = opt.get();
        if (cr.status == null) return SandboxStatus.CREATING;
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
        if (cr.status.podIPs != null && cr.status.podIPs.length > 0) {
            return SandboxStatus.READY;
        }
        return SandboxStatus.CREATING;
    }

    // --- Cleanup ---

    public void cleanupExpiredSandboxes(int maxLifetimeSeconds) {
        if (useWarmPool()) {
            cleanupExpiredClaims(maxLifetimeSeconds);
        } else {
            cleanupExpiredDirectSandboxes(maxLifetimeSeconds);
        }
    }

    private void cleanupExpiredClaims(int maxLifetimeSeconds) {
        try {
            var now = Instant.now();
            var claims = extensionsClient.listClaims("core-ai/component=sandbox");
            for (var claim : claims) {
                cleanupExpiredClaim(claim, now, maxLifetimeSeconds);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to run sandbox claim cleanup", e);
        }
    }

    private void cleanupExpiredClaim(AgentSandboxExtensionsClient.SandboxClaim claim, Instant now, int maxLifetimeSeconds) {
        try {
            if (claim.metadata == null || claim.metadata.creationTimestamp == null) return;
            var created = ZonedDateTime.parse(claim.metadata.creationTimestamp).toInstant();
            var age = Duration.between(created, now);
            if (age.getSeconds() > maxLifetimeSeconds) {
                LOGGER.info("deleting expired sandbox claim: name={}, age={}s", claim.getName(), age.getSeconds());
                deleteServiceSilently("svc-" + claim.getName());
                extensionsClient.deleteClaim(claim.getName());
            }
        } catch (Exception e) {
            LOGGER.warn("failed to cleanup sandbox claim: {}", claim.getName(), e);
        }
    }

    private void cleanupExpiredDirectSandboxes(int maxLifetimeSeconds) {
        try {
            var now = Instant.now();
            var sandboxes = client.listSandboxes("core-ai/component=sandbox");
            for (var cr : sandboxes) {
                cleanupExpiredDirectSandbox(cr, now, maxLifetimeSeconds);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to run agent sandbox cleanup", e);
        }
    }

    private void cleanupExpiredDirectSandbox(AgentSandboxClient.SandboxCR cr, Instant now, int maxLifetimeSeconds) {
        try {
            if (cr.metadata == null || cr.metadata.creationTimestamp == null) return;
            var created = ZonedDateTime.parse(cr.metadata.creationTimestamp).toInstant();
            var age = Duration.between(created, now);
            if (age.getSeconds() > maxLifetimeSeconds) {
                LOGGER.info("deleting expired agent sandbox CR: name={}, age={}s", cr.getName(), age.getSeconds());
                deleteServiceSilently("svc-" + cr.getName());
                client.deleteSandbox(cr.getName());
            }
        } catch (Exception e) {
            LOGGER.warn("failed to cleanup agent sandbox CR: {}", cr.getName(), e);
        }
    }

    private void deleteServiceSilently(String serviceName) {
        if (kubernetesClient == null) return;
        try {
            kubernetesClient.deleteService(serviceName);
        } catch (Exception e) {
            LOGGER.debug("failed to delete service: {}", serviceName, e);
        }
    }

    private String sanitizeLabel(String value) {
        var sanitized = value.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (sanitized.length() > 63) sanitized = sanitized.substring(0, 63);
        sanitized = sanitized.replaceAll("^[^A-Za-z0-9]+", "");
        sanitized = sanitized.replaceAll("[^A-Za-z0-9]+$", "");
        return sanitized.isEmpty() ? "unknown" : sanitized;
    }
}
