package ai.core.server.sandbox;

import ai.core.api.server.session.SandboxEvent;
import ai.core.mcp.client.McpClientManager;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.blob.ObjectStorageServiceResolver;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import ai.core.server.sandboxhub.SessionTokenService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final SandboxManager sandboxManager;
    private final SandboxConfig defaultConfig;
    private final ScheduledExecutorService cleanupScheduler;
    private final String serverUrlFromSandbox;
    private final Map<String, Sandbox> sessionSandboxes = new ConcurrentHashMap<>();
    private final Set<String> persistentSessionIds = ConcurrentHashMap.newKeySet();
    @SuppressWarnings("this-escape")
    private final SandboxFileService sandboxFileService = new SandboxFileService(this);
    private final SessionMcpProcesses sessionMcpProcesses = new SessionMcpProcesses();
    private final DiscoverySandbox discoverySandbox;
    @SuppressFBWarnings("PME_POOR_MANS_ENUM")
    private final boolean enabled;
    ObjectStorageServiceResolver storageResolver;
    final FileService fileService;
    final SessionAttachmentRefRepository attachmentRepository;
    private final SandboxSnapshotService snapshotService;
    private SandboxRedisStore redisStore;
    private volatile SandboxHubBinding hubBinding;

    public SandboxService() {
        this((JedisPool) null, null, null, null, null);
    }

    public SandboxService(JedisPool jedisPool, SandboxSnapshotService snapshotService,
                          ObjectStorageServiceResolver storageResolver, FileService fileService) {
        this(jedisPool, snapshotService, storageResolver, fileService, null);
    }

    public SandboxService(JedisPool jedisPool, SandboxSnapshotService snapshotService,
                          ObjectStorageServiceResolver storageResolver, FileService fileService,
                          SessionAttachmentRefRepository attachmentRepository) {
        this.sandboxManager = null;
        this.discoverySandbox = new DiscoverySandbox(null);
        this.defaultConfig = new SandboxConfig();
        this.defaultConfig.enabled = Boolean.FALSE;
        this.cleanupScheduler = null;
        this.serverUrlFromSandbox = null;
        this.enabled = false;
        this.storageResolver = storageResolver;
        this.fileService = fileService;
        this.attachmentRepository = attachmentRepository;
        this.snapshotService = snapshotService;
        this.redisStore = new SandboxRedisStore(jedisPool);
    }

    public SandboxService(SandboxProvider provider, SandboxConfig defaultConfig) {
        this(provider, defaultConfig, null, new SandboxServiceDependencies(null, null, null, null, null));
    }

    public SandboxService(SandboxProvider provider, SandboxConfig defaultConfig, String serverUrlFromSandbox) {
        this(provider, defaultConfig, serverUrlFromSandbox, new SandboxServiceDependencies(null, null, null, null, null));
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
        this.discoverySandbox = new DiscoverySandbox(sandboxManager);
        this.defaultConfig = defaultConfig != null ? defaultConfig : createDefaultConfig();
        this.serverUrlFromSandbox = serverUrlFromSandbox;
        this.enabled = true;
        this.storageResolver = dependencies.storageResolver();
        this.fileService = dependencies.fileService();
        this.attachmentRepository = dependencies.attachmentRepository();
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

    public Sandbox getOrCreateSandbox(SandboxConfig config, String sessionId, String userId) {
        if (!enabled) return null;
        var effectiveConfig = config != null ? config : defaultConfig;
        if (Boolean.FALSE.equals(effectiveConfig.enabled)) {
            LOGGER.debug("sandbox disabled for session: {}", sessionId);
            return null;
        }
        return sessionSandboxes.computeIfAbsent(sessionId, sid -> {
            LOGGER.info("sandbox created (shared) for session: {}, config={}", sid, effectiveConfig);
            rememberUser(sid, userId);
            return new LazySandbox(effectiveConfig, sandboxManager, null, new LazySandbox.SessionIdentity(sid, userId),
                    () -> onSandboxReady(sid, userId, new SandboxSnapshotService.RestoreResult(
                            SandboxSnapshotService.RestoreOutcome.NONE, null)));
        });
    }

    public Sandbox createSessionSandbox(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> eventDispatcher) {
        return createSessionSandbox(config, sessionId, userId, null, eventDispatcher);
    }

    /** {@code agentName} is informational only — it reaches scripts as {@code CORE_AI_AGENT_NAME}. */
    public Sandbox createSessionSandbox(SandboxConfig config, String sessionId, String userId, String agentName,
                                        Consumer<SandboxEvent> eventDispatcher) {
        rememberAgent(sessionId, agentName);
        return create(config, sessionId, userId, eventDispatcher, snapshotService);
    }

    private Sandbox create(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> eventDispatcher, SandboxSnapshotService snapshot) {
        if (!enabled) return null;
        var effectiveConfig = config != null ? config : defaultConfig;
        if (Boolean.FALSE.equals(effectiveConfig.enabled)) {
            LOGGER.debug("sandbox disabled for session: {}", sessionId);
            return null;
        }
        rememberUser(sessionId, userId);
        var lazySandbox = new LazySandbox(effectiveConfig, sandboxManager, eventDispatcher, new LazySandbox.SessionIdentity(sessionId, userId),
                outcome -> onSandboxReady(sessionId, userId, outcome), snapshot);
        installSandbox(sessionId, lazySandbox);
        LOGGER.info("sandbox created for session: {}, config={}", sessionId, effectiveConfig);
        return lazySandbox;
    }

    // Registers the sandbox as the session's current one. Replacing a live sandbox (session rebuild,
    // re-created ffmpeg lease) must release the previous instance instead of orphaning it.
    private void installSandbox(String sessionId, Sandbox sandbox) {
        var previous = sessionSandboxes.put(sessionId, sandbox);
        if (previous == null || Objects.equals(previous.getId(), sandbox.getId())) return;
        LOGGER.warn("replacing sandbox for session: {}, previous={}, replacement={}",
                sessionId, previous.getId(), sandbox.getId());
        sessionMcpProcesses.stopAll(sessionId, previous);
        closeSandbox(previous);
    }

    /** Releases a sandbox instance regardless of whether it is still lazy (never acquired / tracked). */
    private void closeSandbox(Sandbox sandbox) {
        try {
            if (sandbox instanceof LazySandbox) {
                sandbox.close();
            } else {
                sandboxManager.release(sandbox);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to release replaced sandbox: id={}", sandbox.getId(), e);
        }
    }

    public void addStagedFile(String sessionId, StagedFile file) {
        sandboxFileService.addStagedFile(sessionId, file);
    }
    public void ensurePendingFilesUploaded(String sessionId) {
        sandboxFileService.ensurePendingFilesUploaded(sessionId);
    }
    public void uploadFiles(String sessionId, String userId, List<PendingFile> files) {
        sandboxFileService.uploadFiles(sessionId, userId, files);
    }

    public Sandbox getSandbox(String sessionId) {
        return sessionSandboxes.get(sessionId);
    }
    Sandbox sessionSandbox(String sessionId) {
        return sessionSandboxes.get(sessionId);
    }
    SandboxManager sandboxManager() {
        return sandboxManager;
    }
    public String serverUrlFromSandbox() {
        return serverUrlFromSandbox;
    }

    public Sandbox attachSandbox(String sandboxId, SandboxConfig config, String sessionId, String userId, boolean persistent) {
        if (!enabled) return null;
        var attached = sandboxManager.attach(sandboxId, config, sessionId, userId);
        if (attached.isEmpty()) return null;
        installSandbox(sessionId, attached.get());
        rememberUser(sessionId, userId);
        if (persistent) persistentSessionIds.add(sessionId);
        storeSandboxBinding(sessionId);
        // a reattached sandbox is a fresh runtime as far as the hub is concerned: the pod that bound it
        // may be gone, and the binding only ever lives in the runtime's memory
        if (hubBinding != null) hubBinding.bind(attached.get(), sessionId, userId);
        return attached.get();
    }

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
        if (hubBinding != null) {
            hubBinding.rebindIfNeeded(sandbox, sessionId);
            hubBinding.heal(sandbox, sessionId);
        }
    }

    public void releaseSandbox(String sessionId) {
        if (!enabled) return;
        var sandbox = sessionSandboxes.remove(sessionId);
        persistentSessionIds.remove(sessionId);
        if (hubBinding != null) hubBinding.forget(sessionId);
        sandboxFileService.clear(sessionId);
        sessionMcpProcesses.stopAll(sessionId, sandbox);
        deleteSandboxBinding(sessionId);
        if (hubBinding != null) hubBinding.unbind(sandbox, sessionId);
        if (sandbox != null) {
            closeSandbox(sandbox);
            LOGGER.info("sandbox released for session: {}", sessionId);
        }
    }

    /** Returns the McpClientManager scoped to this session, creating it if needed. */
    public McpClientManager getOrCreateSessionMcpManager(String sessionId) {
        return sessionMcpProcesses.managerFor(sessionId);
    }

    /** Record that an MCP server id was started on the session's sandbox so we can stop it on release. */
    public void recordSessionMcpServer(String sessionId, String serverId) {
        sessionMcpProcesses.record(sessionId, serverId);
    }

    // Called by LazySandbox post-acquire hook after the sandbox materializes.
    // Uploads files queued before the sandbox existed. MCP processes are started
    // lazily at resolveToolRefs time (per-session, on demand), not here.
    private void onSandboxReady(String sessionId, String userId, SandboxSnapshotService.RestoreResult restoreResult) {
        sandboxFileService.restoreAttachments(sessionId, userId, restoreResult.snapshotCreatedAt());
        sandboxFileService.ensurePendingFilesUploaded(sessionId);
        storeSandboxBinding(sessionId);
        if (hubBinding != null) hubBinding.bind(sessionSandboxes.get(sessionId), sessionId, userId);
    }

    // ---- Sandbox hub binding (session identity for the sandbox runtime's loopback hub proxy) ----

    /** Wires the session tokens minted for the sandbox hub; without it scripts cannot reach the hub. */
    public void sessionTokens(SessionTokenService sessionTokenService) {
        hubBinding = new SandboxHubBinding(sessionTokenService, serverUrlFromSandbox,
                () -> SandboxHubBinding.ttlSeconds(defaultConfig));
    }

    /** True while (session, sandbox) still matches the identity held by the sandbox runtime. */
    public boolean isBound(String sessionId, String sandboxId) {
        if (hubBinding == null || sessionId == null || sandboxId == null || sandboxId.isBlank()) return false;
        var sandbox = sessionSandboxes.get(sessionId);
        if (sandbox != null) return hubBinding.isBound(sandbox, sandboxId);
        // The session may live on another replica: the binding stored when the sandbox was acquired is
        // the same identity this pod would see locally, so a replaced or released sandbox still fails.
        return hubBinding.isBound(redisStore != null ? redisStore.getBinding(sessionId) : null, sandboxId);
    }

    private void rememberAgent(String sessionId, String agentName) {
        if (hubBinding != null) hubBinding.rememberAgent(sessionId, agentName);
    }

    private void rememberUser(String sessionId, String userId) {
        if (hubBinding != null) hubBinding.rememberUser(sessionId, userId);
    }

    // ---- Discovery sandbox (global, long-running) ----

    public SandboxClient getDiscoverySandboxClient() {
        if (!enabled) throw new IllegalStateException("sandbox is not enabled");
        return discoverySandbox.client();
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

    /** Strictly removes a durable sandbox binding without releasing a live local sandbox. */
    public void invalidateSandboxBinding(String sessionId) {
        if (redisStore != null) redisStore.deleteBindingStrict(sessionId);
    }

    /** Reattaches during rebuild, returning a LazySandbox around the delegate or null when unavailable. */
    public Sandbox reattachOrCreateSandbox(String sandboxId, SandboxConfig config, String sessionId, String userId,
                                           Consumer<SandboxEvent> eventDispatcher) {
        return reattachOrCreateSandbox(sandboxId, config, sessionId, userId, null, eventDispatcher);
    }

    /** {@code agentName} is informational only — it reaches scripts as {@code CORE_AI_AGENT_NAME}. */
    public Sandbox reattachOrCreateSandbox(String sandboxId, SandboxConfig config, String sessionId, String userId, String agentName,
                                           Consumer<SandboxEvent> eventDispatcher) {
        if (!enabled) return null;
        var effectiveConfig = config != null ? config : defaultConfig;
        if (Boolean.FALSE.equals(effectiveConfig.enabled)) return null;
        var attached = sandboxManager.attach(sandboxId, effectiveConfig, sessionId, userId);
        if (attached.isEmpty()) {
            LOGGER.info("sandbox no longer available for reattach, sessionId={}, sandboxId={}", sessionId, sandboxId);
            return null;
        }
        rememberAgent(sessionId, agentName);
        rememberUser(sessionId, userId);
        var sandbox = attached.get();
        long snapshotEpoch = 0;
        if (snapshotService != null) {
            try {
                snapshotEpoch = snapshotService.beginEpoch(sessionId);
            } catch (RuntimeException e) {
                LOGGER.warn("snapshot epoch allocation failed for reattached sandbox, capture disabled: sessionId={}, sandboxId={}",
                        sessionId, sandbox.getId(), e);
            }
        }
        var lazy = new LazySandbox(sandbox, effectiveConfig, sandboxManager, new LazySandbox.SandboxContext(
                eventDispatcher, new LazySandbox.SessionIdentity(sessionId, userId),
                outcome -> onSandboxReady(sessionId, userId, outcome), snapshotService, snapshotEpoch));
        installSandbox(sessionId, lazy);
        storeSandboxBinding(sessionId);
        if (hubBinding != null) hubBinding.bind(lazy, sessionId, userId);
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
        sessionMcpProcesses.closeAll();
        discoverySandbox.close();
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
