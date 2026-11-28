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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WorkspaceArchiveService} lifecycle, quiescent guards, restore protection,
 * and retention cleanup.
 *
 * @author stephen
 */
class WorkspaceArchiveServiceTest {
    private InMemoryWorkspaces workspaces;
    private InMemoryBindings bindings;
    private InMemoryEvents events;
    private InMemoryArchives archives;
    private StubArchiveClient archiveClient;
    private InMemoryObjectStorage storage;
    private WorkspaceService workspaceService;
    private WorkspaceArchiveService archiveService;

    @BeforeEach
    void setUp() {
        workspaces = new InMemoryWorkspaces();
        var volumes = new InMemoryVolumes();
        bindings = new InMemoryBindings();
        events = new InMemoryEvents();
        var leases = new InMemoryLeases();
        var usage = new InMemoryUsage();
        archives = new InMemoryArchives();
        var provisioner = new StubProvisioner();
        var runtimes = new StubRuntimeManager();
        archiveClient = new StubArchiveClient();
        storage = new InMemoryObjectStorage();
        var config = new WorkspaceConfig();
        config.enabled = true;
        config.archiveRetentionDays = 30;
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
    void createArchiveStreamsToBlobComputesSha256AndAudits() {
        var ws = readyWorkspace("user-1", "dev");

        var archive = archiveService.create("user-1", ws.id);

        assertEquals(WorkspaceArchiveStatus.READY, archive.status);
        assertEquals("workspace/user-1/" + ws.id + "/0/" + archive.id + ".tar.gz", archive.blobKey);
        assertTrue(storage.exists("workspace-archives", archive.blobKey));
        assertFalse(archive.sha256.isBlank());
        assertTrue(archive.sizeBytes > 0);
        assertEquals(4L, archive.fileCount);
        assertTrue(events.types(ws.id).contains("archived"));
    }

    @Test
    void createRejectsWhenTurnAsyncTaskOrTerminalIsActive() {
        var ws = readyWorkspace("user-1", "dev");
        var binding = bindings.get(ws.id).orElseThrow();
        binding.boundSessionId = "sess-1";
        bindings.upsert(binding);
        workspaceService.sessionBusy = "sess-1"::equals;

        var busyTurn = assertThrows(WorkspaceException.class, () -> archiveService.create("user-1", ws.id));
        assertEquals(WorkspaceException.BUSY, busyTurn.errorCode);

        workspaceService.sessionBusy = id -> false;
        archiveClient.quiescentStatus = new WorkspaceArchiveClient.QuiescentStatus(1, 0);
        var busyAsync = assertThrows(WorkspaceException.class, () -> archiveService.create("user-1", ws.id));
        assertEquals(WorkspaceException.BUSY, busyAsync.errorCode);

        archiveClient.quiescentStatus = new WorkspaceArchiveClient.QuiescentStatus(0, 1);
        var busyTerminal = assertThrows(WorkspaceException.class, () -> archiveService.create("user-1", ws.id));
        assertEquals(WorkspaceException.BUSY, busyTerminal.errorCode);
    }

    @Test
    void listExcludesDeletedAndDeleteRemovesBlob() {
        var ws = readyWorkspace("user-1", "dev");
        var first = archiveService.create("user-1", ws.id);
        var second = archiveService.create("user-1", ws.id);

        assertEquals(2, archiveService.list("user-1", ws.id).size());

        archiveService.delete("user-1", ws.id, first.id);

        var remaining = archiveService.list("user-1", ws.id);
        assertEquals(1, remaining.size());
        assertEquals(second.id, remaining.getFirst().id);
        assertFalse(storage.exists("workspace-archives", first.blobKey));
        assertTrue(events.types(ws.id).contains("archive_deleted"));
    }

    @Test
    void restoreWithNewNameCreatesFreshWorkspaceAndMarksReady() {
        var ws = readyWorkspace("user-1", "dev");
        var archive = archiveService.create("user-1", ws.id);

        var restored = archiveService.restore("user-1", ws.id, archive.id, "dev-restored");

        assertEquals("dev-restored", restored.name);
        assertEquals(WorkspaceVolumeStatus.READY, restored.volumeStatus);
        assertEquals(WorkspaceRuntimeStatus.READY, restored.runtimeStatus);
        assertNotNull(restored.bootstrapCompletedAt);
        assertEquals(archive.volumeGeneration, archiveClient.lastRestoreOptions.expectedGeneration());
        assertEquals(ws.id, archiveClient.lastRestoreOptions.expectedWorkspaceId());
        assertFalse(archiveClient.lastRestoreOptions.allowOverwrite());
        assertTrue(events.types(restored.id).contains("restored_from_archive"));
    }

    @Test
    void restoreInPlaceRefusesHealthyTargetVolume() {
        var ws = readyWorkspace("user-1", "dev");
        var archive = archiveService.create("user-1", ws.id);

        var err = assertThrows(WorkspaceException.class,
                () -> archiveService.restore("user-1", ws.id, archive.id, null, true));
        assertEquals(WorkspaceException.RESTORE_TARGET_EXISTS, err.errorCode);
    }

    @Test
    void restoreFailsOnSha256MismatchWithoutChangingWorkspaceStatus() {
        var ws = readyWorkspace("user-1", "dev");
        var archive = archiveService.create("user-1", ws.id);
        storage.corrupt("workspace-archives", archive.blobKey, "tampered-bytes");

        var err = assertThrows(WorkspaceException.class,
                () -> archiveService.restore("user-1", ws.id, archive.id, "dev-copy"));
        assertEquals(WorkspaceException.ARCHIVE_SHA256_MISMATCH, err.errorCode);
        assertEquals(1, workspaces.listByOwner("user-1").size());
        assertEquals(WorkspaceVolumeStatus.READY, workspaces.get(ws.id).orElseThrow().volumeStatus);
        assertTrue(events.types(ws.id).contains("restore_failed"));
    }

    @Test
    void uploadCrashMarksArchiveFailedAndCleansPartialBlob() {
        var ws = readyWorkspace("user-1", "dev");
        storage.failNextUpload.set(true);

        var err = assertThrows(WorkspaceException.class, () -> archiveService.create("user-1", ws.id));
        assertEquals(WorkspaceException.ARCHIVE_FAILED, err.errorCode);

        var visible = archiveService.list("user-1", ws.id);
        assertEquals(1, visible.size());
        assertEquals(WorkspaceArchiveStatus.FAILED, visible.getFirst().status);
        assertFalse(storage.exists("workspace-archives", visible.getFirst().blobKey));
    }

    @Test
    void purgeExpiredDeletesOldBlobsAndMarksDeleted() {
        var ws = readyWorkspace("user-1", "dev");
        var old = archiveService.create("user-1", ws.id);
        var recent = archiveService.create("user-1", ws.id);
        archives.backdate(old.id, ZonedDateTime.now().minusDays(45));

        var sweep = workspaceService.runLifecycleSweep();

        assertEquals(1, sweep.archivesPurged());
        assertEquals(WorkspaceArchiveStatus.DELETED, archives.get(old.id).orElseThrow().status);
        assertFalse(storage.exists("workspace-archives", old.blobKey));
        assertEquals(WorkspaceArchiveStatus.READY, archives.get(recent.id).orElseThrow().status);
        assertTrue(storage.exists("workspace-archives", recent.blobKey));
    }

    private WorkspaceRecord readyWorkspace(String userId, String name) {
        var ws = workspaceService.create(userId, name, null, 32, null, 15);
        workspaceService.wake(userId, ws.id);
        return workspaceService.get(userId, ws.id);
    }

    private static final class StubProvisioner implements WorkspaceProvisioner {
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

    private static final class StubRuntimeManager extends WorkspaceRuntimeManager {
        private final Map<String, String> live = new ConcurrentHashMap<>();

        StubRuntimeManager() {
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
            return live.containsKey(name)
                    ? Optional.of(new AgentSandboxClient.SandboxCR())
                    : Optional.empty();
        }

        @Override
        public Optional<String> endpointHost(String name) {
            return Optional.ofNullable(live.get(name));
        }
    }

    private static final class StubArchiveClient implements WorkspaceArchiveClient {
        QuiescentStatus quiescentStatus = new QuiescentStatus(0, 0);
        RestoreOptions lastRestoreOptions;

        @Override
        public QuiescentStatus probeQuiescent(String host, int port) {
            return quiescentStatus;
        }

        @Override
        public CaptureResult captureToFile(String host, int port, CaptureRequest request, Path targetFile) {
            var content = ("archive:" + request.workspaceId() + ":" + request.generation()).getBytes(StandardCharsets.UTF_8);
            try {
                Files.write(targetFile, content);
                return new CaptureResult(WorkspaceArchiveService.sha256Hex(targetFile), content.length, 4L);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public RestoreResult restoreFromFile(String host, int port, Path archiveFile, RestoreOptions options) {
            this.lastRestoreOptions = options;
            try {
                return new RestoreResult(4L, Files.size(archiveFile));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final class InMemoryObjectStorage implements ObjectStorageService {
        private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        final AtomicBoolean failNextUpload = new AtomicBoolean(false);

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
                var bytes = Files.readAllBytes(file);
                blobs.put(container + "/" + blobName, bytes);
                if (failNextUpload.compareAndSet(true, false)) {
                    throw new RuntimeException("simulated blob upload crash");
                }
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
            if (data == null) throw new RuntimeException("blob not found: " + blobName);
            try {
                Files.write(target, data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public ObjectMetadata headObject(String container, String blobName) {
            var data = blobs.get(container + "/" + blobName);
            return data == null ? null : new ObjectMetadata((long) data.length, "etag", "application/gzip", null);
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

    private static final class InMemoryWorkspaces implements WorkspaceStore {
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
                    .filter(w -> ownerUserId.equals(w.ownerUserId) && name.equals(w.name) && !w.deleted())
                    .findFirst();
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId) {
            return listByOwner(ownerUserId, false);
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId, boolean includeDeleted) {
            return rows.values().stream()
                    .filter(w -> ownerUserId.equals(w.ownerUserId) && (includeDeleted || !w.deleted()))
                    .toList();
        }

        @Override
        public List<WorkspaceRecord> listActive() {
            return rows.values().stream().filter(w -> !w.deleted()).toList();
        }

        @Override
        public List<WorkspaceRecord> listDeletedBefore(ZonedDateTime cutoff) {
            return rows.values().stream().filter(w -> w.deleted() && w.deletedAt.isBefore(cutoff)).toList();
        }

        @Override
        public boolean insertIfAbsent(WorkspaceRecord workspace) {
            if (rows.containsKey(workspace.id)) return false;
            if (findByName(workspace.ownerUserId, workspace.name).isPresent()) return false;
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
            var w = rows.get(id);
            if (w == null) return;
            w.bootstrapCompletedAt = state.bootstrapCompletedAt();
            w.bootstrapScriptHash = state.bootstrapScriptHash();
            w.startScriptHash = state.startScriptHash();
            w.lastBootstrapLog = state.lastBootstrapLog();
            w.envFilePresent = state.envFilePresent();
        }

        @Override
        public void touchLastUsed(String id, ZonedDateTime at) {
            var w = rows.get(id);
            if (w != null) w.lastUsedAt = at;
        }

        @Override
        public void touchWoken(String id, ZonedDateTime at) {
            var w = rows.get(id);
            if (w != null) w.lastWokenAt = at;
        }

        @Override
        public void touchSlept(String id, ZonedDateTime at) {
            var w = rows.get(id);
            if (w != null) w.lastSleptAt = at;
        }

        @Override
        public boolean softDelete(String id, ZonedDateTime at) {
            var w = rows.get(id);
            if (w == null || w.deleted()) return false;
            w.deletedAt = at;
            return true;
        }

        @Override
        public boolean restore(String id) {
            var w = rows.get(id);
            if (w == null || !w.deleted()) return false;
            w.deletedAt = null;
            return true;
        }

        @Override
        public boolean purge(String id) {
            var w = rows.get(id);
            if (w == null || !w.deleted()) return false;
            rows.remove(id);
            return true;
        }

        @Override
        public void makeDefault(String ownerUserId, String agentId, String id) {
        }
    }

    private static final class InMemoryVolumes implements WorkspaceVolumeStore {
        private final List<WorkspaceVolumeRecord> rows = new ArrayList<>();

        @Override
        public Optional<WorkspaceVolumeRecord> get(String workspaceId, long volumeGeneration) {
            return rows.stream()
                    .filter(v -> v.volumeGeneration == volumeGeneration && v.workspaceId.equals(workspaceId))
                    .findFirst();
        }

        @Override
        public Optional<WorkspaceVolumeRecord> active(String workspaceId) {
            return rows.stream()
                    .filter(v -> v.workspaceId.equals(workspaceId)
                            && (v.status == WorkspaceVolumeStatus.PROVISIONING
                            || v.status == WorkspaceVolumeStatus.READY
                            || v.status == WorkspaceVolumeStatus.ERROR))
                    .max(Comparator.comparingLong(v -> v.volumeGeneration));
        }

        @Override
        public List<WorkspaceVolumeRecord> listByWorkspace(String workspaceId) {
            return rows.stream().filter(v -> v.workspaceId.equals(workspaceId)).toList();
        }

        @Override
        public List<WorkspaceVolumeRecord> listByStatus(WorkspaceVolumeStatus status) {
            return rows.stream().filter(v -> v.status == status).toList();
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
            get(workspaceId, volumeGeneration).ifPresent(v -> {
                v.status = status;
                if (v.deletedAt == null) v.deletedAt = ZonedDateTime.now();
            });
        }
    }

    private static final class InMemoryBindings implements WorkspaceBindingStore {
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
            var existing = rows.get(workspaceId);
            if (existing == null) return false;
            existing.boundSessionId = sessionId;
            return true;
        }

        @Override
        public boolean clearSessionIfBound(String workspaceId, String sessionId, ZonedDateTime at) {
            var existing = rows.get(workspaceId);
            if (existing != null && sessionId.equals(existing.boundSessionId)) {
                existing.boundSessionId = null;
                return true;
            }
            return false;
        }

        @Override
        public void delete(String workspaceId) {
            rows.remove(workspaceId);
        }
    }

    private static final class InMemoryEvents implements WorkspaceEventStore {
        private final List<WorkspaceEventRecord> rows = new ArrayList<>();

        @Override
        public void append(WorkspaceEventRecord event) {
            rows.add(event);
        }

        @Override
        public List<WorkspaceEventRecord> list(String workspaceId, int limit) {
            return rows.stream().filter(e -> e.workspaceId.equals(workspaceId)).limit(limit).toList();
        }

        @Override
        public int deleteByWorkspace(String workspaceId) {
            var before = rows.size();
            rows.removeIf(e -> e.workspaceId.equals(workspaceId));
            return before - rows.size();
        }

        List<String> types(String workspaceId) {
            return rows.stream().filter(e -> e.workspaceId.equals(workspaceId)).map(e -> e.eventType).toList();
        }
    }

    private static final class InMemoryLeases implements WorkspaceLeaseStore {
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
            var cur = rows.get(workspaceId);
            if (cur == null || !leaseToken.equals(cur.leaseToken)) return false;
            cur.expiresAt = expiresAt;
            return true;
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
            var removed = 0;
            var it = rows.values().iterator();
            while (it.hasNext()) {
                if (sessionId.equals(it.next().sessionId)) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        }
    }

    private static final class InMemoryUsage implements WorkspaceUsageStore {
        private final Map<String, WorkspaceUsageRecord> rows = new HashMap<>();

        @Override
        public Optional<WorkspaceUsageRecord> get(String workspaceId) {
            return Optional.ofNullable(rows.get(workspaceId));
        }

        @Override
        public Map<String, WorkspaceUsageRecord> listByWorkspaces(List<String> workspaceIds) {
            var out = new HashMap<String, WorkspaceUsageRecord>();
            for (var id : workspaceIds) {
                var r = rows.get(id);
                if (r != null) out.put(id, r);
            }
            return out;
        }

        @Override
        public void upsert(WorkspaceUsageRecord usage) {
            rows.put(usage.workspaceId, usage);
        }

        @Override
        public void delete(String workspaceId) {
            rows.remove(workspaceId);
        }
    }

    private static final class InMemoryArchives implements WorkspaceArchiveStore {
        private final Map<String, WorkspaceArchiveRecord> rows = new HashMap<>();

        void backdate(String id, ZonedDateTime createdAt) {
            var r = rows.get(id);
            if (r != null) r.createdAt = createdAt;
        }

        @Override
        public Optional<WorkspaceArchiveRecord> get(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<WorkspaceArchiveRecord> listVisible(String workspaceId) {
            return rows.values().stream()
                    .filter(a -> workspaceId.equals(a.workspaceId)
                            && (a.status == WorkspaceArchiveStatus.READY || a.status == WorkspaceArchiveStatus.FAILED))
                    .sorted(Comparator.comparing((WorkspaceArchiveRecord a) -> a.createdAt).reversed())
                    .toList();
        }

        @Override
        public List<WorkspaceArchiveRecord> listByWorkspace(String workspaceId) {
            return rows.values().stream().filter(a -> workspaceId.equals(a.workspaceId)).toList();
        }

        @Override
        public List<WorkspaceArchiveRecord> listExpired(ZonedDateTime createdBefore, int limit) {
            return rows.values().stream()
                    .filter(a -> a.status != WorkspaceArchiveStatus.DELETED && a.createdAt.isBefore(createdBefore))
                    .limit(limit)
                    .toList();
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
            a.failureReason = null;
            a.updatedAt = ZonedDateTime.now();
            return true;
        }

        @Override
        public boolean markFailed(String id, String failureReason) {
            var a = rows.get(id);
            if (a == null || a.status != WorkspaceArchiveStatus.CREATING) return false;
            a.status = WorkspaceArchiveStatus.FAILED;
            a.failureReason = failureReason;
            a.updatedAt = ZonedDateTime.now();
            return true;
        }

        @Override
        public boolean markDeleted(String id) {
            var a = rows.get(id);
            if (a == null || a.status == WorkspaceArchiveStatus.DELETED) return false;
            a.status = WorkspaceArchiveStatus.DELETED;
            a.updatedAt = ZonedDateTime.now();
            return true;
        }
    }
}
