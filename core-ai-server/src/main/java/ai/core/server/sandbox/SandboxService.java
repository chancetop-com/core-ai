package ai.core.server.sandbox;

import ai.core.api.server.session.SandboxEvent;
import ai.core.mcp.client.McpClientManager;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class SandboxService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxService.class);

    public static SandboxConfig createDefaultConfig() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        config.memoryLimitMb = 512;
        config.cpuLimitMillicores = 500;
        config.networkEnabled = Boolean.FALSE;
        config.timeoutSeconds = 3900;
        return config;
    }

    private static SandboxConfig createDiscoveryConfig() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        config.memoryLimitMb = 512;
        config.cpuLimitMillicores = 500;
        config.networkEnabled = Boolean.FALSE;
        config.timeoutSeconds = 86_400; // 24 hours
        return config;
    }

    private final SandboxManager sandboxManager;
    private final SandboxConfig defaultConfig;
    private final ScheduledExecutorService cleanupScheduler;
    private final String serverUrlFromSandbox;
    private final Map<String, Sandbox> sessionSandboxes = new ConcurrentHashMap<>();
    private final Set<String> persistentSessionIds = ConcurrentHashMap.newKeySet();
    @SuppressWarnings("this-escape")
    private final SandboxFileService sandboxFileService = new SandboxFileService(this);
    private final Map<String, McpClientManager> sessionMcpManagers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionMcpServerIds = new ConcurrentHashMap<>();

    private volatile LazySandbox discoverySandbox;

    @SuppressFBWarnings("PME_POOR_MANS_ENUM")
    private final boolean enabled;
    final ObjectStorageService storageService;
    final FileService fileService;
    private final SandboxSnapshotService snapshotService;
    private SandboxRedisStore redisStore;

    public SandboxService() {
        this((JedisPool) null, null, null, null);
    }

    public SandboxService(JedisPool jedisPool, SandboxSnapshotService snapshotService,
                          ObjectStorageService storageService, FileService fileService) {
        this.sandboxManager = null;
        this.defaultConfig = new SandboxConfig();
        this.defaultConfig.enabled = Boolean.FALSE;
        this.cleanupScheduler = null;
        this.serverUrlFromSandbox = null;
        this.enabled = false;
        this.storageService = storageService;
        this.fileService = fileService;
        this.snapshotService = snapshotService;
        this.redisStore = new SandboxRedisStore(jedisPool);
    }

    public SandboxService(SandboxProvider provider, SandboxConfig defaultConfig) {
        this(provider, defaultConfig, null, new SandboxServiceDependencies(null, null, null, null));
    }

    public SandboxService(SandboxProvider provider, SandboxConfig defaultConfig, String serverUrlFromSandbox) {
        this(provider, defaultConfig, serverUrlFromSandbox, new SandboxServiceDependencies(null, null, null, null));
    }

    public SandboxService(SandboxProvider provider, SandboxConfig defaultConfig, String serverUrlFromSandbox, SandboxServiceDependencies dependencies) {
        this(provider, defaultConfig, serverUrlFromSandbox, dependencies, new ScheduledThreadPoolExecutor(1, r -> {
            var t = new Thread(r, "sandbox-cleanup");
            t.setDaemon(true);
            return t;
        }));
    }

    SandboxService(SandboxProvider provider, SandboxConfig defaultConfig, String serverUrlFromSandbox,
                   SandboxServiceDependencies dependencies, ScheduledExecutorService cleanupScheduler) {
        this.sandboxManager = new SandboxManager(provider);
        this.defaultConfig = defaultConfig != null ? defaultConfig : createDefaultConfig();
        this.serverUrlFromSandbox = serverUrlFromSandbox;
        this.enabled = true;
        this.storageService = dependencies.storageService();
        this.fileService = dependencies.fileService();
        this.snapshotService = dependencies.snapshotService();
        this.redisStore = new SandboxRedisStore(dependencies.jedisPool());
        this.cleanupScheduler = cleanupScheduler;
        cleanupScheduler.scheduleAtFixedRate(new SandboxCleanupJob(sandboxManager, provider), 5, 5, TimeUnit.MINUTES);
    }

    public Sandbox createSandbox(SandboxConfig config, String sessionId, String userId) {
        return createSandbox(config, sessionId, userId, null);
    }
    public Sandbox createSandbox(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> eventDispatcher) {
        return create(config, sessionId, userId, eventDispatcher, null);
    }
    public Sandbox createSandbox(SandboxConfig config, String sessionId, String userId, boolean persistent) {
        var sandbox = createSandbox(config, sessionId, userId, null);
        if (persistent && sandbox != null) persistentSessionIds.add(sessionId);
        return sandbox;
    }

    // Reuse the session's sandbox if one already exists, else create it — atomically, so concurrent callers (e.g.
    // parallel CODE nodes in one workflow run sharing a single per-run sandbox) don't race to create duplicates.
    // The caller owns the lifecycle: it must releaseSandbox(sessionId) when done.
    public Sandbox getOrCreateSandbox(SandboxConfig config, String sessionId, String userId) {
        if (!enabled) return null;
        var effectiveConfig = config != null ? config : defaultConfig;
        if (Boolean.FALSE.equals(effectiveConfig.enabled)) {
            LOGGER.debug("sandbox disabled for session: {}", sessionId);
            return null;
        }
        return sessionSandboxes.computeIfAbsent(sessionId, sid -> {
            LOGGER.info("sandbox created (shared) for session: {}, config={}", sid, effectiveConfig);
            return new LazySandbox(effectiveConfig, sandboxManager, null, new LazySandbox.SessionIdentity(sid, userId), () -> onSandboxReady(sid));
        });
    }

    /** Chat-session sandboxes only: adds snapshot capture/restore. Run/workflow/OCG sandboxes never snapshot. */
    public Sandbox createSessionSandbox(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> eventDispatcher) {
        return create(config, sessionId, userId, eventDispatcher, snapshotService);
    }

    private Sandbox create(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> eventDispatcher, SandboxSnapshotService snapshot) {
        if (!enabled) return null;
        var effectiveConfig = config != null ? config : defaultConfig;
        if (Boolean.FALSE.equals(effectiveConfig.enabled)) {
            LOGGER.debug("sandbox disabled for session: {}", sessionId);
            return null;
        }
        var lazySandbox = new LazySandbox(effectiveConfig, sandboxManager, eventDispatcher, new LazySandbox.SessionIdentity(sessionId, userId),
                () -> onSandboxReady(sessionId), snapshot);
        sessionSandboxes.put(sessionId, lazySandbox);
        LOGGER.info("sandbox created for session: {}, config={}", sessionId, effectiveConfig);
        return lazySandbox;
    }

    public void addPendingFile(String sessionId, String fileName, String container, String blobName) {
        sandboxFileService.addPendingFile(sessionId, fileName, container, blobName);
    }
    public void addStagedFile(String sessionId, StagedFile file) {
        sandboxFileService.addStagedFile(sessionId, file);
    }
    public void ensurePendingFilesUploaded(String sessionId) {
        sandboxFileService.ensurePendingFilesUploaded(sessionId);
    }
    public void uploadFiles(String sessionId, List<PendingFile> files) {
        sandboxFileService.uploadFiles(sessionId, files);
    }

    public Sandbox getSandbox(String sessionId) {
        return sessionSandboxes.get(sessionId);
    }
    Sandbox sessionSandbox(String sessionId) {
        return sessionSandboxes.get(sessionId);
    }
    public String serverUrlFromSandbox() {
        return serverUrlFromSandbox;
    }

    public Sandbox attachSandbox(String sandboxId, SandboxConfig config, String sessionId, String userId) {
        return attachSandbox(sandboxId, config, sessionId, userId, false);
    }

    public Sandbox attachSandbox(String sandboxId, SandboxConfig config, String sessionId, String userId, boolean persistent) {
        if (!enabled) return null;
        var attached = sandboxManager.attach(sandboxId, config, sessionId, userId);
        if (attached.isEmpty()) return null;
        sessionSandboxes.put(sessionId, attached.get());
        if (persistent) persistentSessionIds.add(sessionId);
        storeSandboxBinding(sessionId);
        return attached.get();
    }

    public void markPersistentSandbox(String sessionId) {
        persistentSessionIds.add(sessionId);
    }

    /** Force a LazySandbox for the session to materialize. No-op when there is no sandbox or it's already ready. */
    public void ensureSandboxReady(String sessionId) {
        var sandbox = sessionSandboxes.get(sessionId);
        if (sandbox instanceof LazySandbox lazy) lazy.ensureReady();
    }

    public void renewSandbox(String sessionId) {
        if (!enabled) return;
        var sandbox = sessionSandboxes.get(sessionId);
        if (sandbox == null) return;
        var id = sandbox.getId();
        if ("pending".equals(id)) return; // not yet acquired, nothing to renew
        sandboxManager.renew(id);
    }

    public void releaseSandbox(String sessionId) {
        if (!enabled) return;
        var sandbox = sessionSandboxes.remove(sessionId);
        persistentSessionIds.remove(sessionId);
        sandboxFileService.clear(sessionId);
        // Stop the MCP processes started in this session's sandbox (best-effort — the
        // sandbox close that follows will reap them anyway, but explicit stop keeps the
        // runtime's process map tidy when sandboxes are pooled/reused).
        var startedIds = sessionMcpServerIds.remove(sessionId);
        if (sandbox != null && startedIds != null && !startedIds.isEmpty()) {
            stopSessionMcpProcesses(sessionId, sandbox, startedIds);
        }
        // Close the per-session McpClientManager — releases Java-side HTTP transports
        // and stops the heartbeat monitor for this session's servers.
        var sessionMgr = sessionMcpManagers.remove(sessionId);
        if (sessionMgr != null) {
            try {
                sessionMgr.close();
            } catch (Exception e) {
                LOGGER.warn("failed to close session mcp manager for session {}", sessionId, e);
            }
        }
        deleteSandboxBinding(sessionId);
        if (sandbox != null) {
            if (sandbox instanceof LazySandbox) {
                sandbox.close();
            } else {
                sandboxManager.release(sandbox);
            }
            LOGGER.info("sandbox released for session: {}", sessionId);
        }
    }

    // ---- Per-session MCP manager ----

    /** Returns the McpClientManager scoped to this session, creating it if needed. */
    public McpClientManager getOrCreateSessionMcpManager(String sessionId) {
        return sessionMcpManagers.computeIfAbsent(sessionId, sid -> new McpClientManager());
    }

    /** Record that an MCP server id was started on the session's sandbox so we can stop it on release. */
    public void recordSessionMcpServer(String sessionId, String serverId) {
        sessionMcpServerIds.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(serverId);
    }

    private void stopSessionMcpProcesses(String sessionId, Sandbox sandbox, Set<String> serverIds) {
        for (var id : serverIds) {
            try {
                sandbox.stopMcpServer(id);
            } catch (Exception e) {
                LOGGER.warn("failed to stop mcp server in session sandbox: session={}, serverId={}: {}", sessionId, id, e.getMessage());
            }
        }
    }

    // Called by LazySandbox post-acquire hook after the sandbox materializes.
    // Uploads files queued before the sandbox existed. MCP processes are started
    // lazily at resolveToolRefs time (per-session, on demand), not here.
    private void onSandboxReady(String sessionId) {
        sandboxFileService.ensurePendingFilesUploaded(sessionId);
        storeSandboxBinding(sessionId);
    }

    // ---- Discovery sandbox (global, long-running) ----

    public SandboxClient getDiscoverySandboxClient() {
        synchronized (this) {
            if (!enabled) throw new IllegalStateException("sandbox is not enabled");
            final int maxAttempts = 3;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (discoverySandbox == null) {
                    var discoveryConfig = createDiscoveryConfig();
                    discoverySandbox = new LazySandbox(discoveryConfig, sandboxManager, null, new LazySandbox.SessionIdentity("discovery", "system"), null);
                    LOGGER.info("discovery sandbox created (attempt {}/{})", attempt + 1, maxAttempts);
                }
                discoverySandbox.ensureReady();
                var ip = discoverySandbox.ip();
                var port = discoverySandbox.port();
                if (ip == null || port == 0) {
                    throw new IllegalStateException("discovery sandbox ip/port not available");
                }
                var client = new SandboxClient(ip, port, SandboxConstants.MCP_STARTUP_TIMEOUT_SECONDS);
                // Quick health check — ensures the sandbox runtime is actually reachable.
                // When the underlying pod has been deleted (e.g. warm-pool template update),
                // the LazySandbox still reports READY with the stale IP. In that case the
                // health check fails, we close & reset discoverySandbox and retry with a
                // freshly acquired pod.
                try {
                    client.waitForReady(5_000);
                    LOGGER.info("discovery sandbox ready: ip={}, port={}", ip, port);
                    return client;
                } catch (Exception e) {
                    LOGGER.warn("discovery sandbox unreachable (attempt {}/{}): ip={}, port={}, error={}",
                            attempt + 1, maxAttempts, ip, port, e.getMessage());
                    discoverySandbox.close();
                    discoverySandbox = null;
                }
            }
            throw new IllegalStateException("discovery sandbox failed after " + maxAttempts + " attempts");
        }
    }

    public boolean hasSandbox(String sessionId) {
        return sessionSandboxes.containsKey(sessionId);
    }

    public boolean isSandboxEnabled(SandboxConfig config) {
        if (!enabled) return false;
        var effectiveConfig = config != null ? config : defaultConfig;
        return !Boolean.FALSE.equals(effectiveConfig.enabled);
    }

    // ---- Redis sandbox binding (cross-pod reattach) ----

    /** Returns the sandbox ID bound to the session in Redis, or null if not found or Redis unavailable. */
    public String getSandboxId(String sessionId) {
        return redisStore != null ? redisStore.getBinding(sessionId) : null;
    }

    private void storeSandboxBinding(String sessionId) {
        if (redisStore == null) return;
        var sandbox = sessionSandboxes.get(sessionId);
        if (sandbox == null) return;
        var id = sandbox.getId();
        if ("pending".equals(id)) return;
        redisStore.saveBinding(sessionId, id);
    }

    private void deleteSandboxBinding(String sessionId) {
        if (redisStore != null) redisStore.deleteBinding(sessionId);
    }

    /**
     * Reattach to an existing sandbox for the session during rebuild.
     * Returns a LazySandbox wrapping the reattached delegate, or null if the sandbox no longer exists.
     */
    public Sandbox reattachOrCreateSandbox(String sandboxId, SandboxConfig config, String sessionId, String userId,
                                           Consumer<SandboxEvent> eventDispatcher) {
        if (!enabled) return null;
        var effectiveConfig = config != null ? config : defaultConfig;
        if (Boolean.FALSE.equals(effectiveConfig.enabled)) return null;
        var attached = sandboxManager.attach(sandboxId, effectiveConfig, sessionId, userId);
        if (attached.isEmpty()) {
            LOGGER.info("sandbox no longer available for reattach, sessionId={}, sandboxId={}", sessionId, sandboxId);
            return null;
        }
        var sandbox = attached.get();
        var lazy = new LazySandbox(sandbox, effectiveConfig, sandboxManager,
                new LazySandbox.SandboxContext(eventDispatcher,
                        new LazySandbox.SessionIdentity(sessionId, userId),
                        () -> onSandboxReady(sessionId), snapshotService));
        sessionSandboxes.put(sessionId, lazy);
        storeSandboxBinding(sessionId);
        LOGGER.info("reattached to existing sandbox, sessionId={}, sandboxId={}", sessionId, sandbox.getId());
        return lazy;
    }

    // ---- Stats / lifecycle ----

    public SandboxConfig getEffectiveConfig(AgentDefinition definition) {
        if (!enabled || definition == null || definition.sandboxConfig == null) return defaultConfig;
        return definition.sandboxConfig.toConfig();
    }

    public SandboxConfig getDefaultConfig() {
        return defaultConfig;
    }

    public Map<String, Object> getStats() {
        if (!enabled) return Map.of("enabled", Boolean.FALSE);
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

        for (var entry : sessionSandboxes.entrySet()) {
            if (persistentSessionIds.contains(entry.getKey())) continue;
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.warn("failed to close sandbox for session: {}", entry.getKey(), e);
            }
        }
        sessionSandboxes.clear();
        persistentSessionIds.clear();
        sandboxFileService.clearAll();
        for (var mgr : sessionMcpManagers.values()) {
            try {
                mgr.close();
            } catch (Exception e) {
                LOGGER.warn("failed to close session mcp manager on shutdown", e);
            }
        }
        sessionMcpManagers.clear();
        sessionMcpServerIds.clear();

        if (discoverySandbox != null) {
            try {
                discoverySandbox.close();
            } catch (Exception e) {
                LOGGER.warn("failed to close discovery sandbox", e);
            }
            discoverySandbox = null;
        }

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
