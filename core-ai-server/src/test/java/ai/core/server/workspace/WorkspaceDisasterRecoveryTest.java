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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure matrix and disaster recovery tests: lost PVC detection, fail-closed tool refusal,
 * in-place DR restore with volume_generation increment, archive mid-stream crash cleanup,
 * mid-restore rollback preserving original volume state, and SHA-256 mismatch protection.
 *
 * @author stephen
 */
class WorkspaceDisasterRecoveryTest {
    private DrWorkspaces workspaces;
    private DrVolumes volumes;
    private DrProvisioner provisioner;
    private DrArchiveClient archiveClient;
    private DrStorage storage;
    private WorkspaceService workspaceService;
    private WorkspaceArchiveService archiveService;

    @BeforeEach
    void setUp() {
        workspaces = new DrWorkspaces();
        volumes = new DrVolumes();
        var bindings = new DrBindings();
        var events = new DrEvents();
        var leases = new DrLeases();
        var usage = new DrUsage();
        var archives = new DrArchives();
        provisioner = new DrProvisioner();
        var runtimes = new DrRuntimes();
        archiveClient = new DrArchiveClient();
        storage = new DrStorage();

        var config = new WorkspaceConfig();
        config.enabled = true;
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
    void lostPvcMarksErrorBlocksToolCallsAndSupportsInPlaceDrRestore() {
        var ws = workspaceService.create("user-1", "prod", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        var backup = archiveService.create("user-1", ws.id);

        // Simulate PVC disappearing out of band in the cluster
        provisioner.dropVolume("ws-" + ws.id + "-g0");

        var current = workspaceService.get("user-1", ws.id);
        assertTrue(archiveService.isVolumeLostOrUnavailable(current));
        assertEquals(WorkspaceVolumeStatus.ERROR, current.volumeStatus);
        assertEquals(WorkspaceException.VOLUME_UNAVAILABLE, current.errorCode);

        // Tool calls must fail closed and must NOT silently create a new empty PVC
        var toolErr = assertThrows(WorkspaceException.class,
                () -> workspaceService.beginTurn("user-1", ws.id, "sess-1", false));
        assertEquals(WorkspaceException.VOLUME_UNAVAILABLE, toolErr.errorCode);
        assertFalse(provisioner.hasPvc("ws-" + ws.id + "-g0"));

        // In-place DR restore increments volume_generation (0 -> 1) and restores workspace to READY
        var recovered = archiveService.restore("user-1", ws.id, backup.id, null);
        assertEquals(WorkspaceVolumeStatus.READY, recovered.volumeStatus);
        assertEquals(WorkspaceRuntimeStatus.READY, recovered.runtimeStatus);
        assertEquals(1L, recovered.activeVolumeGeneration);
        assertTrue(provisioner.hasPvc("ws-" + ws.id + "-g1"));
        assertFalse(archiveService.isVolumeLostOrUnavailable(recovered));

        // Tool turn works again after DR recovery
        var turn = workspaceService.beginTurn("user-1", ws.id, "sess-2", false);
        workspaceService.releaseLease(ws.id, turn.leaseToken());
    }

    @Test
    void noActiveVolumeBranchRestoresAndIncrementsGeneration() {
        var ws = workspaceService.create("user-1", "prod", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        var backup = archiveService.create("user-1", ws.id);

        // Remove active volume row entirely to exercise the active_volume == null branch
        volumes.markDeleted(ws.id, 0L, WorkspaceVolumeStatus.DELETED);
        workspaces.updateVolumeState(ws.id, WorkspaceVolumeStatus.ERROR, 0L,
                WorkspaceException.VOLUME_UNAVAILABLE, "volume purged");

        var recovered = archiveService.restore("user-1", ws.id, backup.id, null);

        assertEquals(WorkspaceVolumeStatus.READY, recovered.volumeStatus);
        assertEquals(WorkspaceRuntimeStatus.READY, recovered.runtimeStatus);
        assertEquals(1L, recovered.activeVolumeGeneration);
    }

    @Test
    void failureMatrixArchiveCrashMidwayLeavesNoReadyRowAndCleansHalfBlob() {
        var ws = workspaceService.create("user-1", "prod", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        storage.crashMidUpload.set(true);

        var err = assertThrows(WorkspaceException.class, () -> archiveService.create("user-1", ws.id));
        assertEquals(WorkspaceException.ARCHIVE_FAILED, err.errorCode);

        var visible = archiveService.list("user-1", ws.id);
        assertEquals(1, visible.size());
        assertEquals(WorkspaceArchiveStatus.FAILED, visible.getFirst().status);
        assertFalse(storage.exists("workspace-archives", visible.getFirst().blobKey));
        assertEquals(0, archiveService.summarizeReady(List.of(ws.id)).size());
    }

    @Test
    void failureMatrixRestoreFailureMidwayPreservesOriginalVolumeState() {
        var ws = workspaceService.create("user-1", "prod", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        var backup = archiveService.create("user-1", ws.id);

        provisioner.dropVolume("ws-" + ws.id + "-g0");
        assertTrue(archiveService.isVolumeLostOrUnavailable(workspaceService.get("user-1", ws.id)));

        archiveClient.failRestore.set(true);
        var err = assertThrows(WorkspaceException.class, () -> archiveService.restore("user-1", ws.id, backup.id, null));
        assertEquals(WorkspaceException.ARCHIVE_FAILED, err.errorCode);

        // Original workspace volume state must remain unchanged (ERROR at gen 0, never READY)
        var after = workspaceService.get("user-1", ws.id);
        assertEquals(WorkspaceVolumeStatus.ERROR, after.volumeStatus);
        assertEquals(0L, after.activeVolumeGeneration);
        assertEquals(WorkspaceVolumeStatus.ERROR, volumes.get(ws.id, 0L).orElseThrow().status);
    }

    @Test
    void failureMatrixSha256MismatchRejectsWithoutSwitchingStatus() {
        var ws = workspaceService.create("user-1", "prod", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        var backup = archiveService.create("user-1", ws.id);
        provisioner.dropVolume("ws-" + ws.id + "-g0");
        assertTrue(archiveService.isVolumeLostOrUnavailable(workspaceService.get("user-1", ws.id)));

        storage.corrupt("workspace-archives", backup.blobKey, "corrupted-stream");
        var err = assertThrows(WorkspaceException.class, () -> archiveService.restore("user-1", ws.id, backup.id, null));
        assertEquals(WorkspaceException.ARCHIVE_SHA256_MISMATCH, err.errorCode);

        var after = workspaceService.get("user-1", ws.id);
        assertEquals(WorkspaceVolumeStatus.ERROR, after.volumeStatus);
        assertEquals(0L, after.activeVolumeGeneration);
        assertFalse(provisioner.hasPvc("ws-" + ws.id + "-g1"));
    }

    @Test
    void failureMatrixRuntimeGenerationMismatchPropagates409Code() {
        var ws = workspaceService.create("user-1", "prod", null, 32, null, 15);
        workspaceService.wake("user-1", ws.id);
        var backup = archiveService.create("user-1", ws.id);
        archiveClient.throwGenerationMismatch.set(true);

        var err = assertThrows(WorkspaceException.class,
                () -> archiveService.restore("user-1", ws.id, backup.id, "prod-copy"));
        assertEquals(WorkspaceException.ARCHIVE_GENERATION_MISMATCH, err.errorCode);
        assertEquals(WorkspaceRuntimeStatus.ERROR, workspaces.findByName("user-1", "prod-copy").orElseThrow().runtimeStatus);
    }

    private static final class DrProvisioner implements WorkspaceProvisioner {
        private final Map<String, Boolean> pvcs = new ConcurrentHashMap<>();

        void dropVolume(String pvcName) {
            pvcs.remove(pvcName);
        }

        boolean hasPvc(String pvcName) {
            return pvcs.containsKey(pvcName);
        }

        @Override
        public String provider() {
            return "dr-stub";
        }

        @Override
        public String volumeName(String workspaceId, long volumeGeneration) {
            return "ws-" + workspaceId + "-g" + volumeGeneration;
        }

        @Override
        public VolumeHandle ensureVolume(WorkspaceRecord workspace, long volumeGeneration) {
            var name = volumeName(workspace.id, volumeGeneration);
            pvcs.put(name, Boolean.TRUE);
            return new VolumeHandle(name, "Bound");
        }

        @Override
        public void deleteVolume(WorkspaceVolumeRecord volume) {
            pvcs.remove(volume.pvcName);
        }

        @Override
        public boolean volumeExists(WorkspaceVolumeRecord volume) {
            return volume != null && pvcs.containsKey(volume.pvcName);
        }
    }

    private static final class DrRuntimes extends WorkspaceRuntimeManager {
        private final Map<String, String> live = new ConcurrentHashMap<>();

        DrRuntimes() {
            super(null, null, null);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String create(WorkspaceRuntimeSpec spec) {
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

    private static final class DrArchiveClient implements WorkspaceArchiveClient {
        final AtomicBoolean failRestore = new AtomicBoolean(false);
        final AtomicBoolean throwGenerationMismatch = new AtomicBoolean(false);

        @Override
        public QuiescentStatus probeQuiescent(String host, int port) {
            return new QuiescentStatus(0, 0);
        }

        @Override
        public CaptureResult captureToFile(String host, int port, CaptureRequest request, Path targetFile) {
            var bytes = ("dr-archive:" + request.workspaceId() + ":" + request.generation()).getBytes(StandardCharsets.UTF_8);
            try {
                Files.write(targetFile, bytes);
                return new CaptureResult(WorkspaceArchiveService.sha256Hex(targetFile), bytes.length, 2L);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public RestoreResult restoreFromFile(String host, int port, Path archiveFile, RestoreOptions options) {
            if (throwGenerationMismatch.compareAndSet(true, false)) {
                throw new WorkspaceException(WorkspaceException.ARCHIVE_GENERATION_MISMATCH, "generation mismatch");
            }
            if (failRestore.compareAndSet(true, false)) {
                throw new RuntimeException("simulated restore crash midway");
            }
            try {
                return new RestoreResult(2L, Files.size(archiveFile));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final class DrStorage implements ObjectStorageService {
        private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        final AtomicBoolean crashMidUpload = new AtomicBoolean(false);

        void corrupt(String container, String blobName, String content) {
            blobs.put(container + "/" + blobName, content.getBytes(StandardCharsets.UTF_8));
        }

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
            try {
                var all = Files.readAllBytes(file);
                if (crashMidUpload.compareAndSet(true, false)) {
                    blobs.put(container + "/" + blobName, Arrays.copyOf(all, Math.max(1, all.length / 2)));
                    throw new RuntimeException("simulated network crash mid-upload");
                }
                blobs.put(container + "/" + blobName, all);
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
            if (data == null) throw new RuntimeException("missing blob");
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

    private static final class DrWorkspaces implements WorkspaceStore {
        private final Map<String, WorkspaceRecord> rows = new HashMap<>();

        @Override
        public Optional<WorkspaceRecord> get(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<WorkspaceRecord> findDefault(String ownerUserId, String agentId) {
            return Optional.empty();
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

    private static final class DrVolumes implements WorkspaceVolumeStore {
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

    private static final class DrBindings implements WorkspaceBindingStore {
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
            var cur = rows.get(workspaceId);
            if (cur == null) return false;
            cur.boundSessionId = sessionId;
            return true;
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

    private static final class DrEvents implements WorkspaceEventStore {
        private final List<WorkspaceEventRecord> rows = new ArrayList<>();

        @Override
        public void append(WorkspaceEventRecord event) {
            rows.add(event);
        }

        @Override
        public List<WorkspaceEventRecord> list(String workspaceId, int limit) {
            return rows.stream().filter(e -> workspaceId.equals(e.workspaceId)).limit(limit).toList();
        }

        @Override
        public int deleteByWorkspace(String workspaceId) {
            return 0;
        }
    }

    private static final class DrLeases implements WorkspaceLeaseStore {
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

    private static final class DrUsage implements WorkspaceUsageStore {
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

    private static final class DrArchives implements WorkspaceArchiveStore {
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
            var out = new HashMap<String, WorkspaceArchiveSummary>();
            for (var id : workspaceIds) {
                var ready = rows.values().stream()
                        .filter(a -> a.status == WorkspaceArchiveStatus.READY && id.equals(a.workspaceId))
                        .toList();
                if (!ready.isEmpty()) {
                    var latest = ready.stream().map(a -> a.createdAt).max(ZonedDateTime::compareTo).orElse(null);
                    out.put(id, new WorkspaceArchiveSummary(ready.size(), latest));
                }
            }
            return out;
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
