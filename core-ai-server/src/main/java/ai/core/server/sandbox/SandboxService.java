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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // Default deadline slightly longer than the session idle threshold (60min) so that an active session's
    // renewed sandbox is reclaimed by session close, not by its own expiry firing first.
    public static SandboxConfig createDefaultConfig() {
        var config = new SandboxConfig();
        config.enabled = true;
        config.memoryLimitMb = 512;
        config.cpuLimitMillicores = 500;
        config.networkEnabled = false;
        config.timeoutSeconds = 3900;
        return config;
    }

    private static SandboxConfig createDiscoveryConfig() {
        var config = new SandboxConfig();
        config.enabled = true;
        config.memoryLimitMb = 512;
        config.cpuLimitMillicores = 500;
        config.networkEnabled = false;
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
    // Per-session McpClientManager + the MCP server ids started on that session's sandbox.
    // Sandbox-hosted MCP servers live in these per-session managers (not the global one),
    // so concurrent sessions don't collide on shared server ids.
    private final Map<String, McpClientManager> sessionMcpManagers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionMcpServerIds = new ConcurrentHashMap<>();

    private volatile LazySandbox discoverySandbox;

    private final boolean enabled;
    ObjectStorageService storageService;
    FileService fileService;
    private SandboxSnapshotService snapshotService;

    public SandboxService() {
        this.sandboxManager = null;
        this.defaultConfig = new SandboxConfig();
        this.defaultConfig.enabled = false;
        this.cleanupScheduler = null;
        this.serverUrlFromSandbox = null;
        this.enabled = false;
    }

    public SandboxService(SandboxProvider provider, SandboxConfig defaultConfig) {
        this(provider, defaultConfig, null);
    }

    public SandboxService(SandboxProvider provider, SandboxConfig defaultConfig, String serverUrlFromSandbox) {
        this.sandboxManager = new SandboxManager(provider);
        this.defaultConfig = defaultConfig != null ? defaultConfig : createDefaultConfig();
        this.serverUrlFromSandbox = serverUrlFromSandbox;
        this.enabled = true;

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "sandbox-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(new SandboxCleanupJob(sandboxManager, provider), 5, 5, TimeUnit.MINUTES);
    }

    public void setStorageService(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    public void setFileService(FileService fileService) {
        this.fileService = fileService;
    }

    public void setSnapshotService(SandboxSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
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
            return new LazySandbox(effectiveConfig, sandboxManager, null, sid, userId, () -> onSandboxReady(sid));
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
        var lazySandbox = new LazySandbox(effectiveConfig, sandboxManager, eventDispatcher, sessionId, userId,
                () -> onSandboxReady(sessionId), snapshot);
        sessionSandboxes.put(sessionId, lazySandbox);
        LOGGER.info("sandbox created for session: {}, config={}", sessionId, effectiveConfig);
        return lazySandbox;
    }

    public void addPendingFile(String sessionId, String fileName, String container, String blobName) {
        sandboxFileService.addPendingFile(sessionId, fileName, container, blobName);
    }

    /** Queue a workflow input file (a FileRecord) to be staged at {@code targetPath} in the session's sandbox. */
    public void addStagedFile(String sessionId, StagedFile file) {
        sandboxFileService.addStagedFile(sessionId, file);
    }

    public void ensurePendingFilesUploaded(String sessionId) {
        sandboxFileService.ensurePendingFilesUploaded(sessionId);
    }

    /** Upload the given files directly to the session's sandbox, bypassing the pendingFiles queue.
     *  Used when file metadata is carried in the command payload (cross-pod safe). */
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
        if (startedIds != null && !startedIds.isEmpty() && sandbox != null) {
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
    }

    // ---- Discovery sandbox (global, long-running) ----

    public SandboxClient getDiscoverySandboxClient() {
        synchronized (this) {
            if (!enabled) throw new IllegalStateException("sandbox is not enabled");
            final int maxAttempts = 3;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (discoverySandbox == null) {
                    var discoveryConfig = createDiscoveryConfig();
                    discoverySandbox = new LazySandbox(discoveryConfig, sandboxManager, null, "discovery", "system", null);
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

    public SandboxConfig getEffectiveConfig(AgentDefinition definition) {
        if (!enabled || definition == null || definition.sandboxConfig == null) return defaultConfig;
        return definition.sandboxConfig.toConfig();
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
