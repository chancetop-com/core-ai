package ai.core.server.sandbox;

import ai.core.api.server.session.SandboxEvent;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.domain.AgentDefinition;
import ai.core.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class SandboxService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxService.class);

    private static SandboxConfig createDefaultConfig() {
        var config = new SandboxConfig();
        config.enabled = true;
        config.memoryLimitMb = 512;
        config.cpuLimitMillicores = 500;
        config.networkEnabled = false;
        config.timeoutSeconds = 1800;
        return config;
    }

    private final SandboxManager sandboxManager;
    private final SandboxConfig defaultConfig;
    private final ScheduledExecutorService cleanupScheduler;
    private final Map<String, Sandbox> sessionSandboxes = new ConcurrentHashMap<>();

    private final boolean enabled;

    public SandboxService(SandboxProvider provider) {
        this(provider, createDefaultConfig());
    }

    public SandboxService() {
        this.sandboxManager = null;
        this.defaultConfig = new SandboxConfig();
        this.defaultConfig.enabled = false;
        this.cleanupScheduler = null;
        this.enabled = false;
    }

    public SandboxService(SandboxProvider provider, SandboxConfig defaultConfig) {
        this.sandboxManager = new SandboxManager(provider);
        this.defaultConfig = defaultConfig != null ? defaultConfig : createDefaultConfig();
        this.enabled = true;

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "sandbox-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(new SandboxCleanupJob(sandboxManager, provider), 5, 5, TimeUnit.MINUTES);
    }

    public Sandbox createSandbox(SandboxConfig config, String sessionId, String userId) {
        return createSandbox(config, sessionId, userId, null);
    }

    public Sandbox createSandbox(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> eventDispatcher) {
        if (!enabled) return null;

        var effectiveConfig = config != null ? config : defaultConfig;

        if (Boolean.FALSE.equals(effectiveConfig.enabled)) {
            LOGGER.debug("sandbox disabled for session: {}", sessionId);
            return null;
        }

        var lazySandbox = new LazySandbox(effectiveConfig, sandboxManager, eventDispatcher, sessionId, userId);
        sessionSandboxes.put(sessionId, lazySandbox);

        LOGGER.info("sandbox created for session: {}, config={}", sessionId, effectiveConfig);
        return lazySandbox;
    }

    public Sandbox getSandbox(String sessionId) {
        return sessionSandboxes.get(sessionId);
    }

    public void releaseSandbox(String sessionId) {
        if (!enabled) return;
        var sandbox = sessionSandboxes.remove(sessionId);
        if (sandbox != null) {
            sandbox.close();
            LOGGER.info("sandbox released for session: {}", sessionId);
        }
    }

    public boolean hasSandbox(String sessionId) {
        return sessionSandboxes.containsKey(sessionId);
    }

    public SandboxConfig getEffectiveConfig(AgentDefinition definition) {
        if (!enabled) return defaultConfig;
        if (definition == null || definition.sandboxConfig == null) {
            return defaultConfig;
        }

        var agentConfig = definition.sandboxConfig.toConfig();
        if (Boolean.FALSE.equals(agentConfig.enabled)) {
            return agentConfig;
        }

        return agentConfig;
    }

    @Deprecated
    public SandboxConfig getSandboxConfig(AgentDefinition definition) {
        return getEffectiveConfig(definition);
    }

    public SandboxConfig getDefaultConfig() {
        return defaultConfig;
    }

    public Map<String, Object> getStats() {
        if (!enabled) return Map.of("enabled", false);
        var stats = sandboxManager.getStats();
        return Map.of(
                "activeSandboxes", stats.get("activeCount"),
                "totalAcquired", stats.get("totalAcquired"),
                "totalReleased", stats.get("totalReleased"),
                "sessionsWithSandbox", sessionSandboxes.size()
        );
    }

    public void shutdown() {
        if (!enabled) return;
        LOGGER.info("shutting down sandbox service");

        // Release all session sandboxes
        for (var entry : sessionSandboxes.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.warn("failed to close sandbox for session: {}", entry.getKey(), e);
            }
        }
        sessionSandboxes.clear();

        // Shutdown cleanup scheduler
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("sandbox service shutdown complete");
    }
}
