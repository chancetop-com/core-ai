package ai.core.server.sandbox;

import ai.core.api.server.session.SandboxEvent;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.blob.ObjectStorageService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.file.FileService;
import ai.core.sandbox.Sandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    private final SandboxManager sandboxManager;
    private final SandboxConfig defaultConfig;
    private final ScheduledExecutorService cleanupScheduler;
    private final Map<String, Sandbox> sessionSandboxes = new ConcurrentHashMap<>();
    private final Map<String, List<PendingFile>> pendingFiles = new ConcurrentHashMap<>();

    private final boolean enabled;
    private ObjectStorageService storageService;
    private FileService fileService;

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

    public void setStorageService(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    public void setFileService(FileService fileService) {
        this.fileService = fileService;
    }

    public Sandbox createSandbox(SandboxConfig config, String sessionId, String userId) {
        return createSandbox(config, sessionId, userId, null);
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
            return new LazySandbox(effectiveConfig, sandboxManager, null, sid, userId, () -> uploadPendingFiles(sid));
        });
    }

    public Sandbox createSandbox(SandboxConfig config, String sessionId, String userId, Consumer<SandboxEvent> eventDispatcher) {
        if (!enabled) return null;

        var effectiveConfig = config != null ? config : defaultConfig;

        if (Boolean.FALSE.equals(effectiveConfig.enabled)) {
            LOGGER.debug("sandbox disabled for session: {}", sessionId);
            return null;
        }

        var lazySandbox = new LazySandbox(effectiveConfig, sandboxManager, eventDispatcher, sessionId, userId,
                () -> uploadPendingFiles(sessionId));
        sessionSandboxes.put(sessionId, lazySandbox);

        LOGGER.info("sandbox created for session: {}, config={}", sessionId, effectiveConfig);
        return lazySandbox;
    }

    public void addPendingFile(String sessionId, String fileName, String container, String blobName) {
        pendingFiles.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new PendingFile(fileName, container, blobName));
        LOGGER.info("[ENQUEUE] pending file added: session={}, file={}, blob={}/{}, totalFilesForSession={}",
                sessionId, fileName, container, blobName, pendingFiles.get(sessionId).size());
    }

    /** Queue a workflow input file (a FileRecord) to be staged at {@code targetPath} in the session's sandbox. */
    public void addStagedFile(String sessionId, StagedFile file) {
        pendingFiles.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new PendingFile(file.fileName(), null, null, file.fileId(), file.targetPath()));
        LOGGER.info("staged file queued: session={}, file={}, target={}", sessionId, file.fileName(), file.targetPath());
    }

    public void ensurePendingFilesUploaded(String sessionId) {
        var files = pendingFiles.get(sessionId);
        if (files == null || files.isEmpty()) {
            return;
        }
        var sandbox = sessionSandboxes.get(sessionId);
        LOGGER.info("[UPLOAD] ensurePendingFilesUploaded called, sessionId={}, sandboxExists={}, sandboxType={}",
                sessionId, sandbox != null, sandbox != null ? sandbox.getClass().getSimpleName() : "null");
        if (sandbox == null) {
            LOGGER.info("no sandbox for session {}, pending files will be uploaded when sandbox is created", sessionId);
            return;
        }
        if (sandbox instanceof LazySandbox lazy) {
            LOGGER.info("[UPLOAD] calling lazy.ensureReady(), sandboxId={}, status={}", lazy.getId(), lazy.getStatus());
            lazy.ensureReady();
            LOGGER.info("[UPLOAD] lazy.ensureReady() returned, sandboxId={}, status={}", lazy.getId(), lazy.getStatus());
        }
        uploadPendingFiles(sessionId);
    }

    private void uploadPendingFiles(String sessionId) {
        var files = pendingFiles.get(sessionId);
        LOGGER.info("[UPLOAD] uploadPendingFiles called, sessionId={}, filesExist={}, fileCount={}",
                sessionId, files != null, files != null ? files.size() : 0);
        if (files == null || files.isEmpty()) {
            LOGGER.info("[UPLOAD] no pending files for sessionId={}, total pending sessions={}", sessionId, pendingFiles.size());
            return;
        }

        var sandbox = sessionSandboxes.get(sessionId);
        if (sandbox == null) {
            LOGGER.warn("no sandbox found for session {} when uploading pending files", sessionId);
            return;
        }

        LOGGER.info("[UPLOAD] uploading {} files for sessionId={}, sandboxId={}", files.size(), sessionId, sandbox.getId());
        for (var file : files) {
            if (file.fileId() != null) {
                // workflow artifact staging: a failure here must be deterministic for the consumer, so it throws
                // (the caller — ensurePendingFilesUploaded before the agent loop / CODE executor — fails the run)
                stageFileRecord(sandbox, sessionId, file);
            } else if (!uploadBlobFile(sandbox, sessionId, file)) {
                return;   // chat upload path keeps its original behavior: abort, keep the queue, retry on next trigger
            }
        }
        pendingFiles.remove(sessionId);
        LOGGER.info("[UPLOAD] all {} files uploaded and removed from queue, sessionId={}", files.size(), sessionId);
    }

    private void stageFileRecord(Sandbox sandbox, String sessionId, PendingFile file) {
        if (fileService == null) {
            throw new IllegalStateException("fileService not configured, cannot stage input file " + file.fileName());
        }
        try {
            var data = fileService.getBytes(fileService.get(file.fileId()));
            sandbox.uploadFile(file.targetPath(), data);
            LOGGER.info("staged file uploaded: session={}, target={}, size={}", sessionId, file.targetPath(), data.length);
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to stage input file " + file.fileName() + " into sandbox: " + e.getMessage(), e);
        }
    }

    private boolean uploadBlobFile(Sandbox sandbox, String sessionId, PendingFile file) {
        if (storageService == null) {
            LOGGER.warn("storageService not configured, cannot upload pending files for session {}", sessionId);
            return false;
        }
        try {
            LOGGER.info("[UPLOAD] downloading blob: container={}, blobName={}", file.container, file.blobName);
            var data = storageService.downloadObject(file.container, file.blobName);
            LOGGER.info("[UPLOAD] blob downloaded: size={} bytes, uploading to /tmp/{}", data.length, file.fileName);
            sandbox.uploadFile("/tmp/" + file.fileName, data);
            LOGGER.info("pending file uploaded: session={}, file={}", sessionId, file.fileName);
            return true;
        } catch (Exception e) {
            LOGGER.error("failed to upload pending file to sandbox: session={}, file={}", sessionId, file.fileName, e);
            return false;
        }
    }

    public Sandbox getSandbox(String sessionId) {
        return sessionSandboxes.get(sessionId);
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
        pendingFiles.remove(sessionId);
        if (sandbox != null) {
            sandbox.close();
            LOGGER.info("sandbox released for session: {}", sessionId);
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
        pendingFiles.clear();

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

    /** A file queued for upload into a session's sandbox once it materializes: either a blob-storage object
     *  landing at {@code /tmp/<fileName>} (chat uploads), or a staged FileRecord landing at {@code targetPath}
     *  (workflow artifact staging — see addStagedFile). */
    public record PendingFile(String fileName, String container, String blobName, String fileId, String targetPath) {
        public PendingFile(String fileName, String container, String blobName) {
            this(fileName, container, blobName, null, null);
        }
    }

    /** A workflow input file to stage into a consumer sandbox at a deterministic path before it starts. */
    public record StagedFile(String fileId, String fileName, String targetPath) {
    }
}
