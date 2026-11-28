package ai.core.server.workspace;

import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.server.sandbox.kubernetes.PvcInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers orphan PVC and Sandbox CR reconciliation inside {@link WorkspaceSweeper}, including data
 * safety guards, grace period, per-sweep caps, and failure isolation.
 *
 * @author stephen
 */
class WorkspaceOrphanReconciliationTest {
    private FakeWorkspaces workspaces;
    private FakeVolumes volumes;
    private FakeBindings bindings;
    private FakeOrphanScanner scanner;
    private WorkspaceConfig config;
    private WorkspaceSweeper sweeper;

    @BeforeEach
    void setUp() {
        workspaces = new FakeWorkspaces();
        volumes = new FakeVolumes();
        bindings = new FakeBindings();
        var leases = new FakeLeases();
        var events = new FakeEvents();
        var provisioner = new FakeProvisioner();
        var runtimes = new FakeRuntimeManager();
        scanner = new FakeOrphanScanner();
        config = new WorkspaceConfig();
        config.enabled = true;
        config.purgeAfterDays = 7;
        config.orphanGraceMinutes = 30;
        config.minRuntimeSeconds = 300;
        sweeper = new WorkspaceSweeper(new WorkspaceSweeper.Deps(
                workspaces, bindings, new WorkspaceLeaseManager(leases, config),
                new WorkspaceVolumeManager(volumes, workspaces, provisioner),
                new WorkspaceRuntimeLifecycle(workspaces, bindings, runtimes),
                new WorkspaceAudit(events)), null);
        sweeper.attachOrphanScanner(scanner);
    }

    @Test
    void deletesOrphanPvcPastGracePeriodAndListsOnce() {
        scanner.pvcs.add(pvc("ws-orphan-g0", "ws-missing", "0", minutesAgo(45)));

        var result = sweeper.run(config, sessionId -> false);

        assertEquals(1, result.orphanPvcsDeleted());
        assertEquals(0, result.orphanSandboxesDeleted());
        assertEquals(List.of("ws-orphan-g0"), scanner.deletedPvcs);
        assertEquals(1, scanner.listPvcCalls.get());
        assertEquals(1, scanner.listSandboxCalls.get());
    }

    @Test
    void keepsYoungOrphanPvcWithinGracePeriod() {
        scanner.pvcs.add(pvc("ws-young-g0", "ws-missing", "0", minutesAgo(10)));

        var result = sweeper.run(config, sessionId -> false);

        assertEquals(0, result.orphanPvcsDeleted());
        assertTrue(scanner.deletedPvcs.isEmpty());
    }

    @Test
    void keepsPvcWhenWorkspaceExistsButVolumeRowIsMissing() {
        var workspace = activeWorkspace("ws-existing");
        workspaces.put(workspace);
        scanner.pvcs.add(pvc("ws-existing-g0", workspace.id, "0", minutesAgo(60)));

        var result = sweeper.run(config, sessionId -> false);

        assertEquals(0, result.orphanPvcsDeleted());
        assertTrue(scanner.deletedPvcs.isEmpty());
    }

    @Test
    void deletesDeletedVolumePvcAndKeepsActiveAndDeletingVolumes() {
        var workspace = activeWorkspace("ws-vols");
        workspaces.put(workspace);
        volumes.put(volume(workspace, 0, "ws-vols-g0", WorkspaceVolumeStatus.DELETED));
        volumes.put(volume(workspace, 1, "ws-vols-g1", WorkspaceVolumeStatus.DELETING));
        volumes.put(volume(workspace, 2, "ws-vols-g2", WorkspaceVolumeStatus.READY));
        volumes.put(volume(workspace, 3, "ws-vols-g3", WorkspaceVolumeStatus.PROVISIONING));
        volumes.put(volume(workspace, 4, "ws-vols-g4", WorkspaceVolumeStatus.ERROR));
        // keep DELETING volume within retryDeletingVolumes' 10-minute backoff so only orphan reconciler sees it
        volumes.get(workspace.id, 1).orElseThrow().deletedAt = ZonedDateTime.now().minusMinutes(2);

        scanner.pvcs.add(pvc("ws-vols-g0", workspace.id, "0", minutesAgo(60)));
        scanner.pvcs.add(pvc("ws-vols-g1", workspace.id, "1", minutesAgo(60)));
        scanner.pvcs.add(pvc("ws-vols-g2", workspace.id, "2", minutesAgo(60)));
        scanner.pvcs.add(pvc("ws-vols-g3", workspace.id, "3", minutesAgo(60)));
        scanner.pvcs.add(pvc("ws-vols-g4", workspace.id, "4", minutesAgo(60)));
        scanner.pvcs.add(nonWorkspacePvc("other-pvc", minutesAgo(60)));

        var result = sweeper.run(config, sessionId -> false);

        assertEquals(1, result.orphanPvcsDeleted());
        assertEquals(List.of("ws-vols-g0"), scanner.deletedPvcs);
    }

    @Test
    void keepsBoundSandboxAndDeletesSupersededAndOrphanSandboxes() {
        var ready = activeWorkspace("ws-ready");
        ready.runtimeStatus = WorkspaceRuntimeStatus.READY;
        ready.lastUsedAt = ZonedDateTime.now();
        workspaces.put(ready);
        var readyBinding = WorkspaceBindingRecord.create(ready.id, "ws-ready-2", "kubernetes", 2L);
        readyBinding.runtimeStartedAt = ZonedDateTime.now().minusMinutes(10);
        bindings.upsert(readyBinding);

        var waking = activeWorkspace("ws-waking");
        waking.runtimeStatus = WorkspaceRuntimeStatus.PROVISIONING;
        workspaces.put(waking);
        bindings.upsert(WorkspaceBindingRecord.create(waking.id, "ws-waking-1", "kubernetes", 1L));

        scanner.sandboxes.add(sandbox("ws-ready-2", ready.id, minutesAgo(60)));
        scanner.sandboxes.add(sandbox("ws-ready-1", ready.id, minutesAgo(60)));
        scanner.sandboxes.add(sandbox("ws-waking-1", waking.id, minutesAgo(60)));
        scanner.sandboxes.add(sandbox("ws-orphan-1", "ws-gone", minutesAgo(60)));
        scanner.sandboxes.add(sandbox("ws-young-1", "ws-gone", minutesAgo(5)));
        scanner.sandboxes.add(nonWorkspaceSandbox("chat-sandbox-1", minutesAgo(60)));

        var result = sweeper.run(config, sessionId -> false);

        assertEquals(2, result.orphanSandboxesDeleted());
        assertEquals(List.of("ws-ready-1", "ws-orphan-1"), scanner.deletedSandboxes);
    }

    @Test
    void listAndDeleteFailuresDoNotAbortSweepOrBlockSleepAndPurge() {
        var idle = activeWorkspace("ws-idle");
        idle.runtimeStatus = WorkspaceRuntimeStatus.READY;
        idle.idleSleepMinutes = 15;
        idle.lastUsedAt = ZonedDateTime.now().minusMinutes(30);
        workspaces.put(idle);
        var binding = WorkspaceBindingRecord.create(idle.id, "ws-idle-1", "kubernetes", 1L);
        binding.runtimeStartedAt = ZonedDateTime.now().minusMinutes(40);
        bindings.upsert(binding);

        var deleted = activeWorkspace("ws-deleted");
        deleted.deletedAt = ZonedDateTime.now().minusDays(10);
        workspaces.put(deleted);
        volumes.put(volume(deleted, 0, "ws-deleted-g0", WorkspaceVolumeStatus.READY));

        scanner.failListPvcs = true;
        scanner.failListSandboxes = true;

        var result = sweeper.run(config, sessionId -> false);

        assertEquals(1, result.slept());
        assertEquals(1, result.purged());
        assertEquals(0, result.orphanPvcsDeleted());
        assertEquals(0, result.orphanSandboxesDeleted());

        scanner.failListPvcs = false;
        scanner.failListSandboxes = false;
        scanner.failDeletePvcName = "ws-bad-g0";
        scanner.pvcs.add(pvc("ws-bad-g0", "ws-missing-1", "0", minutesAgo(60)));
        scanner.pvcs.add(pvc("ws-good-g0", "ws-missing-2", "0", minutesAgo(60)));

        var second = sweeper.run(config, sessionId -> false);

        assertEquals(1, second.orphanPvcsDeleted());
        assertEquals(List.of("ws-good-g0"), scanner.deletedPvcs);
    }

    @Test
    void enforcesPerSweepDeletionCapForPvcsAndSandboxes() {
        for (int i = 0; i < 25; i++) {
            scanner.pvcs.add(pvc("ws-orphan-pvc-" + i, "ws-missing-" + i, "0", minutesAgo(60)));
            scanner.sandboxes.add(sandbox("ws-orphan-sb-" + i, "ws-missing-" + i, minutesAgo(60)));
        }

        var result = sweeper.run(config, sessionId -> false);

        assertEquals(WorkspaceOrphanReconciler.MAX_DELETES_PER_SWEEP, result.orphanPvcsDeleted());
        assertEquals(WorkspaceOrphanReconciler.MAX_DELETES_PER_SWEEP, result.orphanSandboxesDeleted());
        assertEquals(20, scanner.deletedPvcs.size());
        assertEquals(20, scanner.deletedSandboxes.size());
    }

    private WorkspaceRecord activeWorkspace(String id) {
        var workspace = WorkspaceRecord.create("user-1", "name-" + id, "agent-1");
        workspace.id = id;
        workspace.runtimeStatus = WorkspaceRuntimeStatus.NONE;
        workspace.volumeStatus = WorkspaceVolumeStatus.READY;
        return workspace;
    }

    private WorkspaceVolumeRecord volume(WorkspaceRecord workspace, long generation, String pvcName, WorkspaceVolumeStatus status) {
        var record = WorkspaceVolumeRecord.provisioning(workspace, generation, pvcName, "kubernetes");
        record.status = status;
        return record;
    }

    private String minutesAgo(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).toString();
    }

    private PvcInfo pvc(String name, String workspaceId, String generation, String createdAt) {
        var pvc = new PvcInfo();
        pvc.metadata = new PvcInfo.PvcMetadata();
        pvc.metadata.name = name;
        pvc.metadata.creationTimestamp = createdAt;
        pvc.metadata.labels = Map.of(
                "core-ai/component", "workspace",
                "core-ai/workspace-id", workspaceId,
                "core-ai/volume-generation", generation);
        return pvc;
    }

    private PvcInfo nonWorkspacePvc(String name, String createdAt) {
        var pvc = new PvcInfo();
        pvc.metadata = new PvcInfo.PvcMetadata();
        pvc.metadata.name = name;
        pvc.metadata.creationTimestamp = createdAt;
        pvc.metadata.labels = Map.of("core-ai/component", "other");
        return pvc;
    }

    private AgentSandboxClient.SandboxCR sandbox(String name, String workspaceId, String createdAt) {
        var cr = new AgentSandboxClient.SandboxCR();
        cr.metadata = new AgentSandboxClient.Metadata();
        cr.metadata.name = name;
        cr.metadata.creationTimestamp = createdAt;
        cr.metadata.labels = Map.of(
                "core-ai/component", "workspace",
                "core-ai/workspace-id", workspaceId);
        return cr;
    }

    private AgentSandboxClient.SandboxCR nonWorkspaceSandbox(String name, String createdAt) {
        var cr = new AgentSandboxClient.SandboxCR();
        cr.metadata = new AgentSandboxClient.Metadata();
        cr.metadata.name = name;
        cr.metadata.creationTimestamp = createdAt;
        cr.metadata.labels = Map.of("core-ai/component", "sandbox");
        return cr;
    }

    private static final class FakeOrphanScanner implements WorkspaceOrphanScanner {
        final List<PvcInfo> pvcs = new ArrayList<>();
        final List<AgentSandboxClient.SandboxCR> sandboxes = new ArrayList<>();
        final List<String> deletedPvcs = new ArrayList<>();
        final List<String> deletedSandboxes = new ArrayList<>();
        final AtomicInteger listPvcCalls = new AtomicInteger();
        final AtomicInteger listSandboxCalls = new AtomicInteger();
        boolean failListPvcs;
        boolean failListSandboxes;
        String failDeletePvcName;

        @Override
        public List<PvcInfo> listWorkspacePvcs() {
            listPvcCalls.incrementAndGet();
            if (failListPvcs) throw new IllegalStateException("k8s pvc list failed");
            return List.copyOf(pvcs);
        }

        @Override
        public void deletePvc(String pvcName) {
            if (pvcName.equals(failDeletePvcName)) throw new IllegalStateException("k8s pvc delete failed");
            deletedPvcs.add(pvcName);
        }

        @Override
        public List<AgentSandboxClient.SandboxCR> listWorkspaceSandboxes() {
            listSandboxCalls.incrementAndGet();
            if (failListSandboxes) throw new IllegalStateException("k8s sandbox list failed");
            return List.copyOf(sandboxes);
        }

        @Override
        public void deleteSandbox(String sandboxName) {
            deletedSandboxes.add(sandboxName);
        }
    }

    private static final class FakeRuntimeManager extends WorkspaceRuntimeManager {
        final List<String> deleted = new ArrayList<>();

        FakeRuntimeManager() {
            super(null, null, null);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void delete(String name) {
            deleted.add(name);
        }
    }

    private static final class FakeWorkspaces implements WorkspaceStore {
        private final Map<String, WorkspaceRecord> rows = new HashMap<>();

        void put(WorkspaceRecord workspace) {
            rows.put(workspace.id, workspace);
        }

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
            return Optional.empty();
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId) {
            return List.of();
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId, boolean includeDeleted) {
            return List.of();
        }

        @Override
        public List<WorkspaceRecord> listActive() {
            return rows.values().stream().filter(w -> !w.deleted()).toList();
        }

        @Override
        public List<WorkspaceRecord> listDeletedBefore(ZonedDateTime cutoff) {
            return rows.values().stream()
                    .filter(w -> w.deletedAt != null && w.deletedAt.isBefore(cutoff))
                    .toList();
        }

        @Override
        public boolean insertIfAbsent(WorkspaceRecord workspace) {
            return rows.putIfAbsent(workspace.id, workspace) == null;
        }

        @Override
        public void update(WorkspaceRecord workspace) {
            rows.put(workspace.id, workspace);
        }

        @Override
        public void updateVolumeState(String id, WorkspaceVolumeStatus status, Long activeVolumeGeneration,
                                      String errorCode, String errorMessage) {
        }

        @Override
        public void updateRuntimeState(String id, WorkspaceRuntimeStatus status, long runtimeGeneration,
                                       String errorCode, String errorMessage) {
            var workspace = rows.get(id);
            if (workspace != null) {
                workspace.runtimeStatus = status;
                workspace.runtimeGeneration = runtimeGeneration;
            }
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
            var workspace = rows.get(id);
            if (workspace == null || workspace.deletedAt == null) return false;
            rows.remove(id);
            return true;
        }

        @Override
        public void makeDefault(String ownerUserId, String agentId, String id) {
        }
    }

    private static final class FakeVolumes implements WorkspaceVolumeStore {
        private final Map<String, WorkspaceVolumeRecord> rows = new HashMap<>();

        void put(WorkspaceVolumeRecord volume) {
            rows.put(volume.workspaceId + ":" + volume.volumeGeneration, volume);
        }

        @Override
        public Optional<WorkspaceVolumeRecord> get(String workspaceId, long volumeGeneration) {
            return Optional.ofNullable(rows.get(workspaceId + ":" + volumeGeneration));
        }

        @Override
        public Optional<WorkspaceVolumeRecord> active(String workspaceId) {
            return rows.values().stream()
                    .filter(v -> v.workspaceId.equals(workspaceId)
                            && (v.status == WorkspaceVolumeStatus.PROVISIONING || v.status == WorkspaceVolumeStatus.READY
                            || v.status == WorkspaceVolumeStatus.ERROR))
                    .findFirst();
        }

        @Override
        public List<WorkspaceVolumeRecord> listByWorkspace(String workspaceId) {
            return rows.values().stream().filter(v -> v.workspaceId.equals(workspaceId)).toList();
        }

        @Override
        public List<WorkspaceVolumeRecord> listByStatus(WorkspaceVolumeStatus status) {
            return rows.values().stream().filter(v -> v.status == status).toList();
        }

        @Override
        public boolean insertIfAbsent(WorkspaceVolumeRecord volume) {
            put(volume);
            return true;
        }

        @Override
        public void update(WorkspaceVolumeRecord volume) {
            put(volume);
        }

        @Override
        public void updateStatus(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
            var volume = rows.get(workspaceId + ":" + volumeGeneration);
            if (volume != null) volume.status = status;
        }

        @Override
        public void markDeleted(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
            var volume = rows.get(workspaceId + ":" + volumeGeneration);
            if (volume == null) return;
            volume.status = status;
            if (volume.deletedAt == null) volume.deletedAt = ZonedDateTime.now();
        }
    }

    private static final class FakeBindings implements WorkspaceBindingStore {
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
        public boolean clearSessionIfBound(String workspaceId, String sessionId, ZonedDateTime at) {
            return false;
        }

        @Override
        public boolean rebindSession(String workspaceId, String sandboxId, long runtimeGeneration, String sessionId, ZonedDateTime at) {
            return false;
        }

        @Override
        public void delete(String workspaceId) {
            rows.remove(workspaceId);
        }
    }

    private static final class FakeLeases implements WorkspaceLeaseStore {
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
        public boolean replace(String workspaceId, String expectedToken, WorkspaceLeaseRecord lease) {
            var current = rows.get(workspaceId);
            if (current == null || !current.leaseToken.equals(expectedToken)) return false;
            rows.put(workspaceId, lease);
            return true;
        }

        @Override
        public boolean renew(String workspaceId, String leaseToken, ZonedDateTime expiresAt) {
            return true;
        }

        @Override
        public boolean release(String workspaceId, String leaseToken) {
            rows.remove(workspaceId);
            return true;
        }

        @Override
        public int releaseBySession(String sessionId) {
            return 0;
        }

        @Override
        public int releaseByWorkspace(String workspaceId) {
            return rows.remove(workspaceId) != null ? 1 : 0;
        }
    }

    private static final class FakeEvents implements WorkspaceEventStore {
        @Override
        public void append(WorkspaceEventRecord event) {
        }

        @Override
        public List<WorkspaceEventRecord> list(String workspaceId, int limit) {
            return List.of();
        }

        @Override
        public int deleteByWorkspace(String workspaceId) {
            return 0;
        }
    }

    private static final class FakeProvisioner implements WorkspaceProvisioner {
        @Override
        public String provider() {
            return "kubernetes";
        }

        @Override
        public String volumeName(String workspaceId, long volumeGeneration) {
            return "ws-" + workspaceId + "-g" + volumeGeneration;
        }

        @Override
        public VolumeHandle ensureVolume(WorkspaceRecord workspace, long volumeGeneration) {
            return new VolumeHandle(volumeName(workspace.id, volumeGeneration), "Pending");
        }

        @Override
        public void deleteVolume(WorkspaceVolumeRecord volume) {
        }
    }
}
