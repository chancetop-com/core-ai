package ai.core.server.workspace;

import ai.core.sandbox.SandboxConstants;
import ai.core.server.blob.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Orchestrates cold archives and restores of persistent workspaces. Archives stream directly
 * between runtime HTTP endpoints, temporary disk files, and private object storage without
 * materializing archive payloads in JVM heap memory.
 *
 * @author stephen
 */
public class WorkspaceArchiveService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceArchiveService.class);
    private static final String ARCHIVE_CONTENT_TYPE = "application/gzip";
    private static final int HASH_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_FAILURE_REASON_CHARS = 512;
    private static final int PURGE_BATCH_SIZE = 100;

    static String blobKey(String ownerUserId, String workspaceId, long generation, String archiveId) {
        return "workspace/" + ownerUserId + "/" + workspaceId + "/" + generation + "/" + archiveId + ".tar.gz";
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[HASH_BUFFER_SIZE];
            int read;
            while (true) {
                read = in.read(buffer);
                if (read == -1) break;
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String truncateReason(String reason) {
        if (reason == null || reason.isBlank()) return "archive failed";
        if (reason.length() <= MAX_FAILURE_REASON_CHARS) return reason;
        return reason.substring(0, MAX_FAILURE_REASON_CHARS);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("failed to delete temporary workspace archive file: {}", path, e);
        }
    }

    private final WorkspaceService workspaceService;
    private final WorkspaceArchiveClient client;
    private final Supplier<ObjectStorageService> storageSupplier;
    private final WorkspaceArchiveRestorer restorer;

    public WorkspaceArchiveService(WorkspaceService workspaceService, WorkspaceArchiveClient client,
                                   Supplier<ObjectStorageService> storageSupplier) {
        this.workspaceService = workspaceService;
        this.client = client;
        this.storageSupplier = storageSupplier;
        this.restorer = new WorkspaceArchiveRestorer(workspaceService, client);
    }

    public WorkspaceArchiveRecord create(String ownerUserId, String workspaceId) {
        requireArchiveReady();
        var workspace = workspaceService.loadActive(ownerUserId, workspaceId);
        if (workspace.volumeStatus != WorkspaceVolumeStatus.READY || workspace.activeVolumeGeneration == null) {
            throw new WorkspaceException(WorkspaceException.VOLUME_UNAVAILABLE,
                    "workspace volume is not ready for archiving: " + workspaceId);
        }
        verifyQuiescentBeforeWake(workspace);
        var hostAndLease = prepareRuntimeAndLease(ownerUserId, workspace);
        try {
            verifyRuntimeQuiescent(hostAndLease.host());
            return captureAndStore(ownerUserId, workspace, hostAndLease.host());
        } finally {
            workspaceService.archiveAccess().leases().release(workspaceId, hostAndLease.lease().leaseToken);
        }
    }

    public List<WorkspaceArchiveRecord> list(String ownerUserId, String workspaceId) {
        requireEnabledAndStore();
        workspaceService.get(ownerUserId, workspaceId);
        return archiveStore().listVisible(workspaceId);
    }

    public void delete(String ownerUserId, String workspaceId, String archiveId) {
        requireEnabledAndStore();
        workspaceService.get(ownerUserId, workspaceId);
        var archive = archiveStore().get(archiveId)
                .filter(a -> a.status != WorkspaceArchiveStatus.DELETED && workspaceId.equals(a.workspaceId))
                .orElseThrow(() -> new WorkspaceException(WorkspaceException.ARCHIVE_NOT_FOUND, "workspace archive not found: " + archiveId));
        archiveStore().markDeleted(archive.id);
        deleteBlobAsync(archive.blobKey);
        workspaceService.archiveAccess().audit().append(workspaceId, "archive_deleted", ownerUserId, Map.of("archiveId", archive.id));
    }

    public WorkspaceRecord restore(String ownerUserId, String workspaceId, String archiveId, String newName) {
        return restore(ownerUserId, workspaceId, archiveId, newName, false);
    }

    public WorkspaceRecord restore(String ownerUserId, String workspaceId, String archiveId,
                                   String newName, boolean allowOverwrite) {
        requireArchiveReady();
        var source = workspaceService.get(ownerUserId, workspaceId);
        var archive = archiveStore().get(archiveId)
                .filter(a -> workspaceId.equals(a.workspaceId))
                .orElseThrow(() -> new WorkspaceException(WorkspaceException.ARCHIVE_NOT_FOUND, "workspace archive not found: " + archiveId));
        if (archive.status != WorkspaceArchiveStatus.READY) {
            throw new WorkspaceException(WorkspaceException.INVALID_REQUEST, "workspace archive is not READY: " + archiveId);
        }
        var hasNewName = newName != null && !newName.isBlank();
        if (!hasNewName && source.deleted()) {
            throw new WorkspaceException(WorkspaceException.DELETED, "cannot restore in-place into a deleted workspace; specify new_name");
        }
        var storage = requireStorage();
        var container = archiveContainer();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("workspace-restore-", ".tar.gz");
            storage.downloadObjectToFile(container, archive.blobKey, tmp);
            verifySha256(workspaceId, ownerUserId, archive, tmp);
            if (hasNewName) {
                return restorer.restoreToNewWorkspace(ownerUserId, source, archive, newName.trim(), tmp);
            }
            verifyQuiescentBeforeWake(source);
            return restorer.restoreInPlace(ownerUserId, source, archive, allowOverwrite, tmp);
        } catch (IOException e) {
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED, "failed to prepare archive for restore: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    public int purgeExpired() {
        var config = workspaceService.archiveAccess().config();
        var store = archiveStore();
        if (!config.enabled || store == null) return 0;
        var cutoff = ZonedDateTime.now().minusDays(Math.max(1, config.archiveRetentionDays));
        var expired = store.listExpired(cutoff, PURGE_BATCH_SIZE);
        if (expired.isEmpty()) return 0;
        var storage = storageSupplier == null ? null : storageSupplier.get();
        var container = archiveContainer();
        var purged = 0;
        for (var archive : expired) {
            try {
                if (storage != null) {
                    storage.deleteObject(container, archive.blobKey);
                }
                if (store.markDeleted(archive.id)) {
                    purged++;
                }
            } catch (RuntimeException e) {
                LOGGER.warn("failed to purge expired workspace archive, retry next sweep, archive={}", archive.id, e);
            }
        }
        return purged;
    }

    public Map<String, WorkspaceArchiveSummary> summarizeReady(List<String> workspaceIds) {
        var store = archiveStore();
        if (store == null || workspaceIds == null || workspaceIds.isEmpty() || !workspaceService.enabled()) {
            return Map.of();
        }
        return store.summarizeReady(workspaceIds);
    }

    public WorkspaceArchiveSummary summary(String workspaceId) {
        var map = summarizeReady(List.of(workspaceId));
        return map.get(workspaceId);
    }

    public boolean isVolumeLostOrUnavailable(WorkspaceRecord workspace) {
        return workspaceService.archiveAccess().volumeManager().isLostOrUnavailable(workspace);
    }

    private WorkspaceArchiveRecord insertCreatingArchive(String ownerUserId, WorkspaceRecord workspace) {
        var generation = workspace.activeVolumeGeneration;
        var archiveId = UUID.randomUUID().toString();
        var now = ZonedDateTime.now();
        var record = new WorkspaceArchiveRecord();
        record.id = archiveId;
        record.workspaceId = workspace.id;
        record.volumeGeneration = generation;
        record.blobKey = blobKey(ownerUserId, workspace.id, generation, archiveId);
        record.sha256 = "";
        record.sizeBytes = 0L;
        record.image = workspace.image != null ? workspace.image : workspaceService.archiveAccess().config().image;
        record.status = WorkspaceArchiveStatus.CREATING;
        record.createdBy = ownerUserId;
        record.createdAt = now;
        record.updatedAt = now;
        archiveStore().insertIfAbsent(record);
        return record;
    }

    private WorkspaceArchiveRecord captureAndStore(String ownerUserId, WorkspaceRecord workspace, String host) {
        var storage = requireStorage();
        var container = archiveContainer();
        var record = insertCreatingArchive(ownerUserId, workspace);
        Path tmp = null;
        boolean uploadAttempted = false;
        try {
            tmp = Files.createTempFile("workspace-archive-", ".tar.gz");
            var req = new WorkspaceArchiveClient.CaptureRequest(record.id, workspace.id, record.volumeGeneration, record.image);
            var captured = client.captureToFile(host, SandboxConstants.RUNTIME_PORT, req, tmp);
            verifyGenerationUnchanged(workspace.id, record.volumeGeneration);
            uploadAttempted = true;
            storage.uploadObject(container, record.blobKey, tmp, ARCHIVE_CONTENT_TYPE);
            return finalizeReadyArchive(workspace.id, ownerUserId, record, captured);
        } catch (WorkspaceException e) {
            failArchiveCapture(storage, container, record, uploadAttempted, e.getMessage());
            throw e;
        } catch (RuntimeException | IOException e) {
            failArchiveCapture(storage, container, record, uploadAttempted, e.getMessage());
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED, "workspace archive failed: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private WorkspaceArchiveRecord finalizeReadyArchive(String workspaceId, String ownerUserId,
                                                        WorkspaceArchiveRecord record,
                                                        WorkspaceArchiveClient.CaptureResult captured) {
        var store = archiveStore();
        if (!store.markReady(record.id, captured.sha256(), captured.sizeBytes(), captured.fileCount())) {
            throw new WorkspaceException(WorkspaceException.ARCHIVE_FAILED,
                    "failed to transition workspace archive to READY: " + record.id);
        }
        appendArchivedAudit(workspaceId, ownerUserId, record, captured);
        return store.get(record.id).orElse(record);
    }

    private void verifyGenerationUnchanged(String workspaceId, long expectedGeneration) {
        var refreshed = workspaceService.archiveAccess().stores().workspaces().get(workspaceId).orElse(null);
        if (refreshed == null || refreshed.deleted() || refreshed.activeVolumeGeneration == null
                || refreshed.activeVolumeGeneration != expectedGeneration) {
            throw new WorkspaceException(WorkspaceException.ARCHIVE_GENERATION_MISMATCH,
                    "workspace volume generation changed during archive");
        }
    }

    private void appendArchivedAudit(String workspaceId, String ownerUserId, WorkspaceArchiveRecord record,
                                     WorkspaceArchiveClient.CaptureResult captured) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("archiveId", record.id);
        payload.put("volumeGeneration", record.volumeGeneration);
        payload.put("sizeBytes", captured.sizeBytes());
        payload.put("fileCount", captured.fileCount());
        payload.put("sha256", captured.sha256());
        workspaceService.archiveAccess().audit().append(workspaceId, "archived", ownerUserId, payload);
    }

    private void failArchiveCapture(ObjectStorageService storage, String container, WorkspaceArchiveRecord record,
                                    boolean uploadAttempted, String reason) {
        if (uploadAttempted) {
            deleteBlobQuietly(storage, container, record.blobKey);
        }
        archiveStore().markFailed(record.id, truncateReason(reason));
    }

    private void verifySha256(String workspaceId, String ownerUserId, WorkspaceArchiveRecord archive, Path file) throws IOException {
        var actual = sha256Hex(file);
        if (!actual.equalsIgnoreCase(archive.sha256)) {
            workspaceService.archiveAccess().audit().append(workspaceId, "restore_failed", ownerUserId, Map.of(
                    "archiveId", archive.id,
                    "reason", WorkspaceException.ARCHIVE_SHA256_MISMATCH));
            throw new WorkspaceException(WorkspaceException.ARCHIVE_SHA256_MISMATCH,
                    "archive sha256 mismatch: expected=" + archive.sha256 + ", actual=" + actual);
        }
    }

    private void verifyQuiescentBeforeWake(WorkspaceRecord workspace) {
        var access = workspaceService.archiveAccess();
        var binding = access.stores().bindings().get(workspace.id).orElse(null);
        if (binding != null && WorkspaceLeaseManager.active(workspaceService.sessionBusy, binding.boundSessionId)) {
            throw new WorkspaceException(WorkspaceException.BUSY, "workspace has an active turn or async task");
        }
        var lease = access.leases().current(workspace.id);
        if (lease != null && !lease.expired(ZonedDateTime.now())
                && ("maintenance".equals(lease.leaseKind) || WorkspaceLeaseManager.active(workspaceService.sessionBusy, lease.sessionId))) {
            throw new WorkspaceException(WorkspaceException.BUSY, "workspace is currently locked by another operation");
        }
    }

    private RuntimeLease prepareRuntimeAndLease(String ownerUserId, WorkspaceRecord workspace) {
        var access = workspaceService.archiveAccess();
        var binding = access.stores().bindings().get(workspace.id).orElse(null);
        if (workspace.runtimeStatus != WorkspaceRuntimeStatus.READY || binding == null
                || !access.runtimeLifecycle().exists(binding.sandboxId)) {
            workspaceService.wake(ownerUserId, workspace.id);
            binding = access.stores().bindings().get(workspace.id)
                    .orElseThrow(() -> new WorkspaceException(WorkspaceException.WAKING, "workspace runtime is not ready"));
        }
        var lease = access.leases().tryAcquireMaintenance(workspace.id, binding.runtimeGeneration,
                workspaceService.sessionBusy);
        if (lease == null) {
            throw new WorkspaceException(WorkspaceException.BUSY, "workspace is busy with an active turn or maintenance operation");
        }
        var host = access.runtimes().endpointHost(binding.sandboxId).orElse(null);
        if (host == null) {
            access.leases().release(workspace.id, lease.leaseToken);
            throw new WorkspaceException(WorkspaceException.WAKE_FAILED,
                    "workspace runtime endpoint unavailable: " + binding.sandboxId);
        }
        return new RuntimeLease(host, lease);
    }

    private void verifyRuntimeQuiescent(String host) {
        var status = client.probeQuiescent(host, SandboxConstants.RUNTIME_PORT);
        if (status == null || status.quiescent()) return;
        if (status.activeAsyncTasks() > 0) {
            throw new WorkspaceException(WorkspaceException.BUSY, "workspace has active async tasks");
        }
        throw new WorkspaceException(WorkspaceException.BUSY, "workspace has an active terminal session");
    }

    private void deleteBlobAsync(String blobKey) {
        var storage = storageSupplier == null ? null : storageSupplier.get();
        if (storage == null) return;
        var container = archiveContainer();
        Executor executor = workspaceService.archiveAccess().wakeExecutor();
        if (executor != null) {
            executor.execute(() -> deleteBlobQuietly(storage, container, blobKey));
        } else {
            deleteBlobQuietly(storage, container, blobKey);
        }
    }

    private void deleteBlobQuietly(ObjectStorageService storage, String container, String blobKey) {
        try {
            storage.deleteObject(container, blobKey);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to delete workspace archive blob: container={}, key={}", container, blobKey, e);
        }
    }

    private void requireArchiveReady() {
        requireEnabledAndStore();
        if (!workspaceService.runtimeAvailable() || client == null) {
            throw new WorkspaceException(WorkspaceException.UNSUPPORTED, "workspace runtime provider is not configured");
        }
        requireStorage();
    }

    private void requireEnabledAndStore() {
        if (!workspaceService.enabled()) {
            throw new WorkspaceException(WorkspaceException.UNSUPPORTED, "persistent workspace is not enabled");
        }
        if (archiveStore() == null) {
            throw new WorkspaceException(WorkspaceException.UNSUPPORTED, "workspace archive store is not configured");
        }
    }

    private ObjectStorageService requireStorage() {
        var storage = storageSupplier == null ? null : storageSupplier.get();
        if (storage == null) {
            throw new WorkspaceException(WorkspaceException.UNSUPPORTED, "object storage is not configured for workspace archives");
        }
        return storage;
    }

    private WorkspaceArchiveStore archiveStore() {
        return workspaceService.archiveAccess().stores().archives();
    }

    private String archiveContainer() {
        var configured = workspaceService.archiveAccess().config().archiveContainer;
        return configured == null || configured.isBlank() ? WorkspaceDefaults.ARCHIVE_CONTAINER : configured.trim();
    }

    private record RuntimeLease(String host, WorkspaceLeaseRecord lease) {
    }
}
