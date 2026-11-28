package ai.core.server.workspace;

import ai.core.server.blob.ObjectStorageService;
import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for Stage 3 image upgrade checkpoints and rollback semantics.
 *
 * @author stephen
 */
class WorkspaceImageUpgradeTest {
    private EventLog events;
    private FakeRuntimes runtimes;
    private FakeStorage storage;
    private WorkspaceService workspaceService;
    private WorkspaceArchiveService archiveService;

    @BeforeEach
    void setUp() {
        var workspaces = new MapWorkspaces();
        var volumes = new ListVolumes();
        var bindings = new MapBindings();
        events = new EventLog();
        var leases = new MapLeases();
        var usage = new MapUsage();
        var archives = new MapArchives();
        var provisioner = new SimpleProvisioner();
        runtimes = new FakeRuntimes();
        var archiveClient = new SimpleArchiveClient();
        storage = new FakeStorage();

        var config = new WorkspaceConfig();
        config.enabled = true;
        config.image = "core-ai-sandbox:v1";
        config.archiveContainer = "workspace-archives";

        var stores = new WorkspaceStores(workspaces, volumes, bindings, events, leases, usage, archives);
        var usageService = new WorkspaceUsageService(workspaces, bindings, usage, runtimes, null);
        workspaceService = new WorkspaceService(stores,
                new WorkspaceService.RuntimeDeps(provisioner, runtimes, usageService, null),
                config, Runnable::run);
        archiveService = new WorkspaceArchiveService(workspaceService, archiveClient, () -> storage);
        workspaceService.attachArchiveService(archiveService);
    }

    @Test
    void upgradeCreatesCheckpointArchiveRebuildsRuntimeKeepingVolumeAndAudits() {
        var ws = workspaceService.create("user-1", "dev", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        var originalVolGen = workspaceService.get("user-1", ws.id).activeVolumeGeneration;
        var originalRuntimeGen = workspaceService.get("user-1", ws.id).runtimeGeneration;

        var upgraded = workspaceService.update("user-1", ws.id,
                new WorkspaceUpdateSpec(null, null, null, null, "core-ai-sandbox:v2", null));

        assertEquals("core-ai-sandbox:v2", upgraded.image);
        assertEquals(originalVolGen, upgraded.activeVolumeGeneration);
        assertTrue(upgraded.runtimeGeneration > originalRuntimeGen);
        assertEquals(WorkspaceRuntimeStatus.READY, upgraded.runtimeStatus);

        var archives = archiveService.list("user-1", ws.id);
        assertEquals(1, archives.size());
        assertEquals(WorkspaceArchiveStatus.READY, archives.getFirst().status);

        var upgradeEvent = events.list(ws.id, 10).stream()
                .filter(e -> "image_upgraded".equals(e.eventType))
                .findFirst().orElseThrow();
        assertTrue(upgradeEvent.payloadJson.contains("core-ai-sandbox:v1"));
        assertTrue(upgradeEvent.payloadJson.contains("core-ai-sandbox:v2"));
        assertTrue(upgradeEvent.payloadJson.contains(archives.getFirst().id));
    }

    @Test
    void upgradeRefusedWhenCheckpointArchiveFails() {
        var ws = workspaceService.create("user-1", "dev", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        storage.failUpload.set(true);

        var err = assertThrows(WorkspaceException.class, () -> workspaceService.update("user-1", ws.id,
                new WorkspaceUpdateSpec(null, null, null, null, "core-ai-sandbox:v2", null)));
        assertEquals(WorkspaceException.UPGRADE_REQUIRES_ARCHIVE, err.errorCode);
        assertTrue(err.getMessage().contains("checkpoint archive failed"));
        assertEquals("core-ai-sandbox:v1", workspaceService.get("user-1", ws.id).image);
    }

    @Test
    void rollbackRestoresVolumeAndSwitchesBackToFromImageWhenUpgradeStartFails() {
        var ws = workspaceService.create("user-1", "dev", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        var olderArchive = archiveService.create("user-1", ws.id);

        runtimes.failOnImage = "core-ai-sandbox:broken-v2";
        var wakeErr = assertThrows(WorkspaceException.class, () -> workspaceService.update("user-1", ws.id,
                new WorkspaceUpdateSpec(null, null, null, null, "core-ai-sandbox:broken-v2", null)));
        assertEquals(WorkspaceException.WAKE_FAILED, wakeErr.errorCode);

        var failedWs = workspaceService.get("user-1", ws.id);
        assertEquals(WorkspaceRuntimeStatus.ERROR, failedWs.runtimeStatus);
        assertEquals("core-ai-sandbox:broken-v2", failedWs.image);

        var checkpoint = archiveService.list("user-1", ws.id).stream()
                .filter(a -> !a.id.equals(olderArchive.id))
                .findFirst().orElseThrow();

        // Non-checkpoint archive must be refused even with allowOverwrite=true
        var wrongArchive = assertThrows(WorkspaceException.class,
                () -> archiveService.restore("user-1", ws.id, olderArchive.id, null, true));
        assertEquals(WorkspaceException.RESTORE_TARGET_EXISTS, wrongArchive.errorCode);

        // Checkpoint archive without allowOverwrite must be refused
        var noFlag = assertThrows(WorkspaceException.class,
                () -> archiveService.restore("user-1", ws.id, checkpoint.id, null, false));
        assertEquals(WorkspaceException.RESTORE_TARGET_EXISTS, noFlag.errorCode);

        // Checkpoint archive with allowOverwrite=true rolls back to v1 and marks READY
        var rolledBack = archiveService.restore("user-1", ws.id, checkpoint.id, null, true);
        assertEquals("core-ai-sandbox:v1", rolledBack.image);
        assertEquals(WorkspaceRuntimeStatus.READY, rolledBack.runtimeStatus);
        assertTrue(events.list(ws.id, 10).stream().anyMatch(e -> "image_rollback".equals(e.eventType)));

        // Once recovered, allowOverwrite=true is refused again
        var afterRecovery = assertThrows(WorkspaceException.class,
                () -> archiveService.restore("user-1", ws.id, checkpoint.id, null, true));
        assertEquals(WorkspaceException.RESTORE_TARGET_EXISTS, afterRecovery.errorCode);
    }

    private static final class FakeRuntimes extends WorkspaceRuntimeManager {
        private final Map<String, String> live = new ConcurrentHashMap<>();
        String failOnImage;

        FakeRuntimes() {
            super(null, null, null);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String create(WorkspaceRuntimeSpec spec) {
            if (failOnImage != null && failOnImage.equals(spec.image)) {
                throw new RuntimeException("image pull failed: " + spec.image);
            }
            var name = name(spec);
            live.put(name, "127.0.0.1");
            return name;
        }

        @Override
        public void waitReady(String name, int timeoutSeconds) {
        }

        @Override
        public void delete(String name) {
            live.remove(name);
        }

        @Override
        public Optional<AgentSandboxClient.SandboxCR> get(String name) {
            return live.containsKey(name) ? Optional.of(new AgentSandboxClient.SandboxCR()) : Optional.empty();
        }

        @Override
        public Optional<String> endpointHost(String name) {
            return Optional.ofNullable(live.get(name));
        }
    }

    private static final class SimpleProvisioner implements WorkspaceProvisioner {
        private final Map<String, Boolean> existing = new ConcurrentHashMap<>();

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public String volumeName(String workspaceId, long volumeGeneration) {
            return "ws-" + workspaceId + "-g" + volumeGeneration;
        }

        @Override
        public VolumeHandle ensureVolume(WorkspaceRecord workspace, long volumeGeneration) {
            var name = volumeName(workspace.id, volumeGeneration);
            existing.put(name, Boolean.TRUE);
            return new VolumeHandle(name, "Bound");
        }

        @Override
        public void deleteVolume(WorkspaceVolumeRecord volume) {
            existing.remove(volume.pvcName);
        }

        @Override
        public boolean volumeExists(WorkspaceVolumeRecord volume) {
            return volume != null && existing.containsKey(volume.pvcName);
        }
    }

    private static final class SimpleArchiveClient implements WorkspaceArchiveClient {
        @Override
        public QuiescentStatus probeQuiescent(String host, int port) {
            return new QuiescentStatus(0, 0);
        }

        @Override
        public CaptureResult captureToFile(String host, int port, CaptureRequest request, Path targetFile) {
            var data = ("checkpoint:" + request.workspaceId() + ":" + request.image()).getBytes(StandardCharsets.UTF_8);
            try {
                Files.write(targetFile, data);
                return new CaptureResult(WorkspaceArchiveService.sha256Hex(targetFile), data.length, 3L);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public RestoreResult restoreFromFile(String host, int port, Path archiveFile, RestoreOptions options) {
            try {
                return new RestoreResult(3L, Files.size(archiveFile));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final class FakeStorage implements ObjectStorageService {
        private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        final AtomicBoolean failUpload = new AtomicBoolean(false);

        @Override
        public UploadCredential generateUploadCredential(String container, String blobName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DownloadCredential generateDownloadCredential(String container, String blobName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] downloadObject(String container, String blobName) {
            return blobs.get(container + "/" + blobName);
        }

        @Override
        public void uploadObject(String container, String blobName, Path file) {
            uploadObject(container, blobName, file, null);
        }

        @Override
        public void uploadObject(String container, String blobName, Path file, String contentType) {
            if (failUpload.compareAndSet(true, false)) {
                throw new RuntimeException("blob storage unreachable");
            }
            try {
                blobs.put(container + "/" + blobName, Files.readAllBytes(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void uploadObject(String container, String blobName, byte[] data, String contentType) {
            blobs.put(container + "/" + blobName, data.clone());
        }

        @Override
        public void downloadObjectToFile(String container, String blobName, Path target) {
            var data = blobs.get(container + "/" + blobName);
            if (data == null) throw new RuntimeException("blob missing");
            try {
                Files.write(target, data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public ObjectMetadata headObject(String container, String blobName) {
            return null;
        }

        @Override
        public boolean exists(String container, String blobName) {
            return blobs.containsKey(container + "/" + blobName);
        }

        @Override
        public void deleteObject(String container, String blobName) {
            blobs.remove(container + "/" + blobName);
        }
    }

    private static final class EventLog implements WorkspaceEventStore {
        private final List<WorkspaceEventRecord> rows = new ArrayList<>();

        @Override
        public void append(WorkspaceEventRecord event) {
            rows.add(event);
        }

        @Override
        public List<WorkspaceEventRecord> list(String workspaceId, int limit) {
            var filtered = new ArrayList<>(rows.stream().filter(e -> workspaceId.equals(e.workspaceId)).toList());
            Collections.reverse(filtered);
            return filtered.stream().limit(limit).toList();
        }

        @Override
        public int deleteByWorkspace(String workspaceId) {
            var before = rows.size();
            rows.removeIf(e -> workspaceId.equals(e.workspaceId));
            return before - rows.size();
        }
    }

    private static final class MapWorkspaces implements WorkspaceStore {
        private final Map<String, WorkspaceRecord> rows = new HashMap<>();

        @Override
        public Optional<WorkspaceRecord> get(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<WorkspaceRecord> findDefault(String ownerUserId, String agentId) {
            return rows.values().stream()
                    .filter(w -> w.isDefault && !w.deleted() && ownerUserId.equals(w.ownerUserId) && agentId.equals(w.agentId))
                    .findFirst();
        }

        @Override
        public Optional<WorkspaceRecord> findByName(String ownerUserId, String name) {
            return rows.values().stream()
                    .filter(w -> !w.deleted() && ownerUserId.equals(w.ownerUserId) && name.equals(w.name))
                    .findFirst();
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId) {
            return listByOwner(ownerUserId, false);
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId, boolean includeDeleted) {
            return rows.values().stream()
                    .filter(w -> (includeDeleted || !w.deleted()) && ownerUserId.equals(w.ownerUserId))
                    .toList();
        }

        @Override
        public List<WorkspaceRecord> listActive() {
            return rows.values().stream().filter(w -> !w.deleted()).toList();
        }

        @Override
        public List<WorkspaceRecord> listDeletedBefore(ZonedDateTime cutoff) {
            return List.of();
        }

        @Override
        public boolean insertIfAbsent(WorkspaceRecord workspace) {
            if (rows.containsKey(workspace.id)) return false;
            rows.put(workspace.id, workspace);
            return true;
        }

        @Override
        public void update(WorkspaceRecord workspace) {
            rows.put(workspace.id, workspace);
        }

        @Override
        public void updateVolumeState(String id, WorkspaceVolumeStatus status, Long activeVolumeGeneration,
                                      String errorCode, String errorMessage) {
            var w = rows.get(id);
            if (w == null) return;
            w.volumeStatus = status;
            w.activeVolumeGeneration = activeVolumeGeneration;
            w.errorCode = errorCode;
            w.errorMessage = errorMessage;
        }

        @Override
        public void updateRuntimeState(String id, WorkspaceRuntimeStatus status, long runtimeGeneration,
                                       String errorCode, String errorMessage) {
            var w = rows.get(id);
            if (w == null) return;
            w.runtimeStatus = status;
            w.runtimeGeneration = runtimeGeneration;
            w.errorCode = errorCode;
            w.errorMessage = errorMessage;
        }

        @Override
        public void updateBootstrapState(String id, WorkspaceBootstrapState state) {
        }

        @Override
        public void touchLastUsed(String id, ZonedDateTime at) {
        }

        @Override
        public void touchWoken(String id, ZonedDateTime at) {
        }

        @Override
        public void touchSlept(String id, ZonedDateTime at) {
        }

        @Override
        public boolean softDelete(String id, ZonedDateTime at) {
            return false;
        }

        @Override
        public boolean restore(String id) {
            return false;
        }

        @Override
        public boolean purge(String id) {
            return false;
        }

        @Override
        public void makeDefault(String ownerUserId, String agentId, String id) {
        }
    }

    private static final class ListVolumes implements WorkspaceVolumeStore {
        private final List<WorkspaceVolumeRecord> rows = new ArrayList<>();

        @Override
        public Optional<WorkspaceVolumeRecord> get(String workspaceId, long volumeGeneration) {
            return rows.stream().filter(v -> v.volumeGeneration == volumeGeneration && workspaceId.equals(v.workspaceId)).findFirst();
        }

        @Override
        public Optional<WorkspaceVolumeRecord> active(String workspaceId) {
            return rows.stream()
                    .filter(v -> workspaceId.equals(v.workspaceId)
                            && (v.status == WorkspaceVolumeStatus.PROVISIONING
                            || v.status == WorkspaceVolumeStatus.READY
                            || v.status == WorkspaceVolumeStatus.ERROR))
                    .max(Comparator.comparingLong(v -> v.volumeGeneration));
        }

        @Override
        public List<WorkspaceVolumeRecord> listByWorkspace(String workspaceId) {
            return rows.stream().filter(v -> workspaceId.equals(v.workspaceId)).toList();
        }

        @Override
        public List<WorkspaceVolumeRecord> listByStatus(WorkspaceVolumeStatus status) {
            return List.of();
        }

        @Override
        public boolean insertIfAbsent(WorkspaceVolumeRecord volume) {
            if (get(volume.workspaceId, volume.volumeGeneration).isPresent()) return false;
            rows.add(volume);
            return true;
        }

        @Override
        public void update(WorkspaceVolumeRecord volume) {
        }

        @Override
        public void updateStatus(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
            get(workspaceId, volumeGeneration).ifPresent(v -> v.status = status);
        }

        @Override
        public void markDeleted(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
            get(workspaceId, volumeGeneration).ifPresent(v -> v.status = status);
        }
    }

    private static final class MapBindings implements WorkspaceBindingStore {
        private final Map<String, WorkspaceBindingRecord> rows = new HashMap<>();

        @Override
        public Optional<WorkspaceBindingRecord> get(String workspaceId) {
            return Optional.ofNullable(rows.get(workspaceId));
        }

        @Override
        public void upsert(WorkspaceBindingRecord binding) {
            rows.put(binding.workspaceId, binding);
        }

        @Override
        public boolean rebindSession(String workspaceId, String sandboxId, long runtimeGeneration, String sessionId, ZonedDateTime at) {
            return false;
        }

        @Override
        public boolean clearSessionIfBound(String workspaceId, String sessionId, ZonedDateTime at) {
            return false;
        }

        @Override
        public void delete(String workspaceId) {
            rows.remove(workspaceId);
        }
    }

    private static final class MapLeases implements WorkspaceLeaseStore {
        private final Map<String, WorkspaceLeaseRecord> rows = new HashMap<>();

        @Override
        public Optional<WorkspaceLeaseRecord> get(String workspaceId) {
            return Optional.ofNullable(rows.get(workspaceId));
        }

        @Override
        public boolean insert(WorkspaceLeaseRecord lease) {
            if (rows.containsKey(lease.workspaceId)) return false;
            rows.put(lease.workspaceId, lease);
            return true;
        }

        @Override
        public boolean replace(String workspaceId, String expectedToken, WorkspaceLeaseRecord replacement) {
            var cur = rows.get(workspaceId);
            if (cur == null || !expectedToken.equals(cur.leaseToken)) return false;
            rows.put(workspaceId, replacement);
            return true;
        }

        @Override
        public boolean renew(String workspaceId, String leaseToken, ZonedDateTime expiresAt) {
            return false;
        }

        @Override
        public boolean release(String workspaceId, String leaseToken) {
            var cur = rows.get(workspaceId);
            if (cur == null || !leaseToken.equals(cur.leaseToken)) return false;
            rows.remove(workspaceId);
            return true;
        }

        @Override
        public int releaseByWorkspace(String workspaceId) {
            return rows.remove(workspaceId) != null ? 1 : 0;
        }

        @Override
        public int releaseBySession(String sessionId) {
            return 0;
        }
    }

    private static final class MapUsage implements WorkspaceUsageStore {
        @Override
        public Optional<WorkspaceUsageRecord> get(String workspaceId) {
            return Optional.empty();
        }

        @Override
        public Map<String, WorkspaceUsageRecord> listByWorkspaces(List<String> workspaceIds) {
            return Map.of();
        }

        @Override
        public void upsert(WorkspaceUsageRecord usage) {
        }

        @Override
        public void delete(String workspaceId) {
        }
    }

    private static final class MapArchives implements WorkspaceArchiveStore {
        private final Map<String, WorkspaceArchiveRecord> rows = new HashMap<>();

        @Override
        public Optional<WorkspaceArchiveRecord> get(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<WorkspaceArchiveRecord> listVisible(String workspaceId) {
            return rows.values().stream()
                    .filter(a -> (a.status == WorkspaceArchiveStatus.READY || a.status == WorkspaceArchiveStatus.FAILED)
                            && workspaceId.equals(a.workspaceId))
                    .toList();
        }

        @Override
        public List<WorkspaceArchiveRecord> listByWorkspace(String workspaceId) {
            return List.of();
        }

        @Override
        public List<WorkspaceArchiveRecord> listExpired(ZonedDateTime createdBefore, int limit) {
            return List.of();
        }

        @Override
        public Map<String, WorkspaceArchiveSummary> summarizeReady(List<String> workspaceIds) {
            return Map.of();
        }

        @Override
        public boolean insertIfAbsent(WorkspaceArchiveRecord archive) {
            if (rows.containsKey(archive.id)) return false;
            rows.put(archive.id, archive);
            return true;
        }

        @Override
        public boolean markReady(String id, String sha256, long sizeBytes, Long fileCount) {
            var a = rows.get(id);
            if (a == null || a.status != WorkspaceArchiveStatus.CREATING) return false;
            a.status = WorkspaceArchiveStatus.READY;
            a.sha256 = sha256;
            a.sizeBytes = sizeBytes;
            a.fileCount = fileCount;
            return true;
        }

        @Override
        public boolean markFailed(String id, String failureReason) {
            var a = rows.get(id);
            if (a == null || a.status != WorkspaceArchiveStatus.CREATING) return false;
            a.status = WorkspaceArchiveStatus.FAILED;
            a.failureReason = failureReason;
            return true;
        }

        @Override
        public boolean markDeleted(String id) {
            var a = rows.get(id);
            if (a == null || a.status == WorkspaceArchiveStatus.DELETED) return false;
            a.status = WorkspaceArchiveStatus.DELETED;
            return true;
        }
    }
}
