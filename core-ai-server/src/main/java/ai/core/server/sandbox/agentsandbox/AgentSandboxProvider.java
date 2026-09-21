package ai.core.server.sandbox.agentsandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.kubernetes.KubernetesClient;
import core.framework.json.JSON;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * @author stephen
 */
public class AgentSandboxProvider implements SandboxProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSandboxProvider.class);
    private static final int DEFAULT_LIFETIME_SECONDS = 3600;

    /**
     * Lifetime written as the sandbox deadline (K8s {@code shutdownTime}) when the agent config does
     * not set one. Acquire and renew have to agree on this value — a shorter renewal would retire a
     * sandbox that is still in use.
     */
    static int lifetimeSeconds(SandboxConfig config) {
        return config != null && config.timeoutSeconds != null && config.timeoutSeconds > 0
                ? config.timeoutSeconds
                : DEFAULT_LIFETIME_SECONDS;
    }

    private final AgentSandboxClient client;
    private final AgentSandboxExtensionsClient extensionsClient;
    private final SandboxConfig defaultConfig;
    private final KubernetesClient kubernetesClient;
    private final boolean useHostPort;
    private final String templateName;
    private final String warmPoolName;
    private final AgentSandboxCleanupService cleanupService;

    public AgentSandboxProvider(AgentSandboxProviderConfig config) {
        this.client = config.client;
        this.extensionsClient = config.extensionsClient;
        this.defaultConfig = config.defaultConfig != null ? config.defaultConfig : new SandboxConfig();
        this.kubernetesClient = config.kubernetesClient;
        this.useHostPort = config.useHostPort;
        this.templateName = config.templateName;
        this.warmPoolName = config.warmPoolName != null ? config.warmPoolName : "default";
        this.cleanupService = new AgentSandboxCleanupService(client, extensionsClient, kubernetesClient);
    }

    private boolean warmPoolConfigured() {
        return extensionsClient != null && templateName != null && !templateName.isBlank();
    }

    // A SandboxClaim only carries template + warm pool + lifecycle, so config that changes how the
    // sandbox itself is built (image, env) has to be created directly. Resource sizing stays owned by
    // the warm pool template — treating a configured limit as "custom" would route every session away
    // from the pool.
    private boolean hasCustomConfig(SandboxConfig config) {
        if (config == null) return false;
        if (config.image != null && !SandboxConstants.DEFAULT_IMAGE.equals(config.image)) return true;
        if (config.env != null && !config.env.isEmpty()) return true;
        return false;
    }

    @Override
    public Sandbox acquire(SandboxConfig config, String sessionId, String userId) {
        if (warmPoolConfigured() && !hasCustomConfig(config)) {
            return acquireFromWarmPool(config, sessionId, userId);
        }
        return acquireDirect(config, sessionId, userId);
    }

    // --- Warm pool mode: create SandboxClaim ---

    @SuppressFBWarnings("ITU_INAPPROPRIATE_TOSTRING_USE")
    private Sandbox acquireFromWarmPool(SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        effectiveConfig.validate();
        var lifetime = lifetimeSeconds(effectiveConfig);

        var claimName = "claim-" + UUID.randomUUID().toString().substring(0, 8);
        LOGGER.info("creating SandboxClaim from warm pool: name={}, template={}, pool={}, sessionId={}",
                claimName, templateName, warmPoolName, sessionId);

        var claimManifest = new SandboxClaimSpecBuilder(claimName, templateName, warmPoolName,
                new SandboxClaimSpecBuilder.Owner(sessionId, userId), lifetime).build();
        var claimJson = JSON.toJSON(claimManifest);
        extensionsClient.createClaim(claimJson);

        // Warm pool should assign a pre-warmed sandbox almost instantly
        var claim = extensionsClient.waitForReady(claimName, 60_000);

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
            return acquireClaimWithNodePort(claim, claimName, lifetime, effectiveConfig.image);
        }

        var sandbox = new AgentSandbox(new AgentSandbox.Config(claimName, null, host, port, lifetime, effectiveConfig.image,
                claim.status.sandbox.name, AgentSandboxKind.CLAIM));
        sandbox.waitForReady();
        return sandbox;
    }

    private Sandbox acquireClaimWithNodePort(AgentSandboxExtensionsClient.SandboxClaim claim, String claimName, int lifetimeSeconds, String image) {
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
            var sandbox = new AgentSandbox(new AgentSandbox.Config(claimName, serviceName, "localhost", nodePort, lifetimeSeconds, image,
                    sandboxName, AgentSandboxKind.CLAIM));
            sandbox.waitForReady();
            return sandbox;
        } catch (Exception e) {
            LOGGER.error("failed to create NodePort service for sandbox claim: {}", claimName, e);
            extensionsClient.deleteClaim(claimName);
            throw new RuntimeException("Failed to create sandbox claim service", e);
        }
    }

    // --- Direct mode: create Sandbox CR ---

    private Sandbox acquireDirect(SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        effectiveConfig.validate();
        var lifetime = lifetimeSeconds(effectiveConfig);

        var specBuilder = new SandboxCRSpecBuilder(effectiveConfig, sessionId, userId, lifetime);
        var crName = specBuilder.sandboxName();

        LOGGER.info("creating agent sandbox CR: name={}, sessionId={}", crName, sessionId);

        var crManifest = specBuilder.build();
        var crJson = JSON.toJSON(crManifest);
        client.createSandbox(crJson);

        var cr = client.waitForReady(crName, 120_000);
        waitForSandboxPodReady(cr);

        if (useHostPort && kubernetesClient != null) {
            return acquireWithNodePort(cr, crName, lifetime, effectiveConfig.image);
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
        var sandbox = new AgentSandbox(new AgentSandbox.Config(crName, null, host, port, lifetime, effectiveConfig.image, null,
                AgentSandboxKind.DIRECT));
        sandbox.waitForReady();
        return sandbox;
    }

    private void waitForSandboxPodReady(AgentSandboxClient.SandboxCR cr) {
        if (kubernetesClient == null || cr.status == null || cr.status.selector == null || cr.status.selector.isBlank()) {
            return;
        }
        try {
            kubernetesClient.waitForReadyBySelector(cr.status.selector, 120_000);
            LOGGER.info("agent sandbox pod ready: selector={}", cr.status.selector);
        } catch (Exception e) {
            LOGGER.warn("sandbox pod readiness wait failed, continuing to runtime health check: selector={}", cr.status.selector, e);
        }
    }

    private Sandbox acquireWithNodePort(AgentSandboxClient.SandboxCR cr, String crName, int lifetimeSeconds, String image) {
        var serviceName = "svc-" + crName;
        try {
            var selectorLabel = cr.status.selector;
            if (selectorLabel == null || selectorLabel.isBlank()) {
                selectorLabel = "agents.x-k8s.io/sandbox-name=" + crName;
            }
            var serviceInfo = kubernetesClient.createNodePortServiceBySelector(serviceName, selectorLabel, SandboxConstants.RUNTIME_PORT);
            var nodePort = serviceInfo.spec.ports[0].nodePort;
            LOGGER.info("created NodePort service for agent sandbox: {} -> localhost:{}", serviceName, nodePort);
            var sandbox = new AgentSandbox(new AgentSandbox.Config(crName, serviceName, "localhost", nodePort, lifetimeSeconds, image, null,
                    AgentSandboxKind.DIRECT));
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
    public Optional<Sandbox> attach(String sandboxId, SandboxConfig config, String sessionId, String userId) {
        var effectiveConfig = config != null ? config : defaultConfig;
        var lifetime = lifetimeSeconds(effectiveConfig);
        var kind = AgentSandboxKind.fromId(sandboxId);
        var attached = attach(kind, sandboxId, lifetime, effectiveConfig.image);
        if (attached.isPresent()) return attached;
        // A stored sandbox id outlives provider reconfiguration (e.g. the warm pool was switched off),
        // so try the other provisioning mode before reporting the sandbox as gone.
        return attach(kind.other(), sandboxId, lifetime, effectiveConfig.image);
    }

    private Optional<Sandbox> attach(AgentSandboxKind kind, String sandboxId, int lifetimeSeconds, String image) {
        return kind == AgentSandboxKind.CLAIM
                ? attachClaim(sandboxId, lifetimeSeconds, image)
                : attachDirect(sandboxId, lifetimeSeconds, image);
    }

    private Optional<Sandbox> attachClaim(String claimName, int lifetimeSeconds, String image) {
        if (extensionsClient == null) return Optional.empty();
        var claimOpt = extensionsClient.getClaim(claimName);
        if (claimOpt.isEmpty()) return Optional.empty();
        var claim = claimOpt.get();
        if (claim.status == null || claim.status.sandbox == null) return Optional.empty();
        var sandboxName = claim.status.sandbox.name;
        String host;
        int port = SandboxConstants.RUNTIME_PORT;
        String serviceName = null;
        if (useHostPort && kubernetesClient != null) {
            serviceName = "svc-" + claimName;
            var nodePort = findNodePort(serviceName);
            if (nodePort == null) return Optional.empty();
            host = "localhost";
            port = nodePort;
        } else if (claim.status.sandbox.podIPs != null && claim.status.sandbox.podIPs.length > 0) {
            host = claim.status.sandbox.podIPs[0];
        } else {
            return Optional.empty();
        }
        var sandbox = new AgentSandbox(new AgentSandbox.Config(claimName, serviceName, host, port, lifetimeSeconds, image, sandboxName,
                AgentSandboxKind.CLAIM));
        return Optional.of(sandbox);
    }

    private Optional<Sandbox> attachDirect(String crName, int lifetimeSeconds, String image) {
        var crOpt = client.getSandbox(crName);
        if (crOpt.isEmpty()) return Optional.empty();
        var cr = crOpt.get();
        if (cr.status == null) return Optional.empty();
        String host;
        int port = SandboxConstants.RUNTIME_PORT;
        String serviceName = null;
        if (useHostPort && kubernetesClient != null) {
            serviceName = "svc-" + crName;
            var nodePort = findNodePort(serviceName);
            if (nodePort == null) return Optional.empty();
            host = "localhost";
            port = nodePort;
        } else if (cr.status.serviceFQDN != null && !cr.status.serviceFQDN.isBlank()) {
            host = cr.status.serviceFQDN;
        } else if (cr.status.podIPs != null && cr.status.podIPs.length > 0) {
            host = cr.status.podIPs[0];
        } else {
            return Optional.empty();
        }
        var sandbox = new AgentSandbox(new AgentSandbox.Config(crName, serviceName, host, port, lifetimeSeconds, image, null,
                AgentSandboxKind.DIRECT));
        return Optional.of(sandbox);
    }

    private Integer findNodePort(String serviceName) {
        var services = kubernetesClient.listServices("component=sandbox");
        return services.stream()
                .filter(service -> service.spec != null && service.spec.ports != null && service.spec.ports.length > 0
                        && serviceName.equals(service.metadata.name))
                .map(service -> service.spec.ports[0].nodePort)
                .filter(port -> port != null)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void release(Sandbox sandbox) {
        if (sandbox == null) return;
        if (!(sandbox instanceof AgentSandbox agentSandbox)) {
            sandbox.close();
            return;
        }
        var id = sandbox.getId();
        LOGGER.info("releasing agent sandbox: kind={}, name={}", agentSandbox.kind(), id);
        try {
            if (kubernetesClient != null && agentSandbox.serviceName() != null) {
                kubernetesClient.deleteService(agentSandbox.serviceName());
            }
            if (agentSandbox.kind() == AgentSandboxKind.CLAIM) {
                if (extensionsClient != null) {
                    extensionsClient.deleteClaim(id);
                } else {
                    LOGGER.warn("no extensions client configured, sandbox claim left to expire: name={}", id);
                }
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
    public void renew(Sandbox sandbox, SandboxConfig config) {
        if (!(sandbox instanceof AgentSandbox agentSandbox)) return;
        var id = sandbox.getId();
        var shutdownTime = Instant.now().plus(lifetimeSeconds(config), ChronoUnit.SECONDS).toString();
        try {
            if (agentSandbox.kind() == AgentSandboxKind.CLAIM) {
                if (extensionsClient == null) {
                    LOGGER.warn("no extensions client configured, cannot renew sandbox claim: name={}", id);
                    return;
                }
                extensionsClient.patchShutdownTime(id, shutdownTime);
            } else {
                client.patchShutdownTime(id, shutdownTime);
            }
            LOGGER.debug("renewed agent sandbox lifetime: name={}, shutdownTime={}", id, shutdownTime);
        } catch (Exception e) {
            // Renewal is best-effort: the in-memory deadline is already extended, next message retries.
            LOGGER.warn("failed to renew agent sandbox lifetime: name={}", id, e);
        }
    }

    @Override
    public SandboxStatus getStatus(Sandbox sandbox) {
        if (sandbox == null) return SandboxStatus.TERMINATED;
        if (!(sandbox instanceof AgentSandbox agentSandbox)) return sandbox.getStatus();
        try {
            return agentSandbox.kind() == AgentSandboxKind.CLAIM ? getClaimStatus(agentSandbox) : getDirectStatus(agentSandbox);
        } catch (Exception e) {
            LOGGER.warn("failed to get agent sandbox status: name={}", sandbox.getId(), e);
            return sandbox.getStatus();
        }
    }

    private SandboxStatus getClaimStatus(AgentSandbox sandbox) {
        if (extensionsClient == null) {
            // Cannot verify: reporting a terminal state here would destroy a sandbox that is still alive.
            LOGGER.warn("no extensions client configured, keeping local sandbox status: name={}", sandbox.getId());
            return sandbox.getStatus();
        }
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

    private SandboxStatus getDirectStatus(AgentSandbox sandbox) {
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
        cleanupService.cleanupExpiredSandboxes(maxLifetimeSeconds);
    }
}
