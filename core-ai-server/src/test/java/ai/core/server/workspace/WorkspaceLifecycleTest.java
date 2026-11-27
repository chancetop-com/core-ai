package ai.core.server.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLifecycleTest {
    private final FakeWorkspaces workspaces = new FakeWorkspaces();
    private final FakeVolumes volumes = new FakeVolumes();
    private final FakeBindings bindings = new FakeBindings();
    private final FakeLeases leases = new FakeLeases();
    private final FakeEvents events = new FakeEvents();
    private final FakeUsage usage = new FakeUsage();
    private final FakeProvisioner provisioner = new FakeProvisioner();
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        var config = new WorkspaceConfig();
        config.enabled = true;
        config.purgeAfterDays = 7;
        var stores = new WorkspaceStores(workspaces, volumes, bindings, events, leases, usage);
        service = new WorkspaceService(stores, provisioner, new WorkspaceRuntimeManager(null, null, null), config, Runnable::run);
    }

    @Test
    void createAppliesIdleSleepOverride() {
        var workspace = service.create("user-1", "scratch", null, null, null, 90);

        assertEquals(90, workspace.idleSleepMinutes);
        assertEquals(WorkspaceDefaults.SIZE_GB, workspace.sizeGb);
    }

    @Test
    void createRejectsIdleSleepOutsideRange() {
        assertThrows(WorkspaceException.class, () -> service.create("user-1", "scratch", null, null, null, 0));
    }

    @Test
    void resolveDefaultAppliesAgentSizeAndProfileOnCreation() {
        var workspace = service.resolveDefault("user-1", "agent-1", 64, "workspace-build");

        assertTrue(workspace.isDefault);
        assertEquals("agent-1", workspace.agentId);
        assertEquals(64, workspace.sizeGb);
        assertEquals(WorkspaceProfile.BUILD, workspace.profile);
    }

    @Test
    void resolveDefaultFallsBackForUnknownAgentProfile() {
        var workspace = service.resolveDefault("user-1", "agent-1", null, "no-such-profile");

        assertEquals(WorkspaceProfile.DEFAULT, workspace.profile);
    }

    @Test
    void purgesDeletedWorkspaceAfterRetention() {
        var workspace = deletedWorkspace(10);
        volumes.put(volume(workspace, WorkspaceVolumeStatus.READY));
        events.count = 2;
        var sample = new WorkspaceUsageRecord();
        sample.workspaceId = workspace.id;
        sample.usedBytes = 1024L;
        sample.capacityBytes = 4096L;
        sample.sampledAt = ZonedDateTime.now();
        usage.upsert(sample);

        var result = service.runLifecycleSweep();

        assertEquals(1, result.purged());
        assertEquals(List.of("ws-g0"), provisioner.deleted);
        assertTrue(workspaces.missing(workspace.id));
        assertEquals(WorkspaceVolumeStatus.DELETED, volumes.status(workspace.id));
        assertEquals(0, events.count);
        assertTrue(usage.get(workspace.id).isEmpty());
    }

    @Test
    void keepsWorkspaceInsideRetention() {
        var workspace = deletedWorkspace(2);
        volumes.put(volume(workspace, WorkspaceVolumeStatus.READY));

        var result = service.runLifecycleSweep();

        assertEquals(0, result.purged());
        assertTrue(provisioner.deleted.isEmpty());
        assertFalse(workspaces.missing(workspace.id));
    }

    @Test
    void keepsRowWhenPvcDeletionFailsSoTheNextSweepRetries() {
        var workspace = deletedWorkspace(10);
        volumes.put(volume(workspace, WorkspaceVolumeStatus.READY));
        provisioner.fail = true;

        var result = service.runLifecycleSweep();

        assertEquals(0, result.purged());
        assertFalse(workspaces.missing(workspace.id));
        assertEquals(WorkspaceVolumeStatus.DELETING, volumes.status(workspace.id));

        provisioner.fail = false;
        assertEquals(1, service.runLifecycleSweep().purged());
        assertTrue(workspaces.missing(workspace.id));
    }

    @Test
    void restoreIsRefusedOnceTheVolumeIsBeingPurged() {
        var workspace = deletedWorkspace(10);
        volumes.put(volume(workspace, WorkspaceVolumeStatus.DELETING));

        var error = assertThrows(WorkspaceException.class, () -> service.restore(workspace.ownerUserId, workspace.id));

        assertEquals(WorkspaceException.DELETED, error.errorCode);
        assertNotNull(workspace.deletedAt);
    }

    @Test
    void purgeSkipsAWorkspaceRestoredBeforeItRuns() {
        var workspace = deletedWorkspace(10);
        volumes.put(volume(workspace, WorkspaceVolumeStatus.READY));
        workspaces.stalePurgeCandidate = workspace;   // listDeletedBefore handed out a row that was restored afterwards
        workspace.deletedAt = null;

        var result = service.runLifecycleSweep();

        assertEquals(0, result.purged());
        assertTrue(provisioner.deleted.isEmpty());
        assertFalse(workspaces.missing(workspace.id));
        assertEquals(WorkspaceVolumeStatus.READY, volumes.status(workspace.id));
    }

    private WorkspaceRecord deletedWorkspace(int daysAgo) {
        var workspace = WorkspaceRecord.create("user-1", "default", "agent-1");
        workspace.runtimeStatus = WorkspaceRuntimeStatus.NONE;
        workspace.volumeStatus = WorkspaceVolumeStatus.READY;
        workspace.activeVolumeGeneration = 0L;
        workspace.deletedAt = ZonedDateTime.now().minusDays(daysAgo);
        workspace.createdAt = workspace.deletedAt.minusDays(30);
        workspace.updatedAt = workspace.deletedAt;
        workspaces.put(workspace);
        return workspace;
    }

    private WorkspaceVolumeRecord volume(WorkspaceRecord workspace, WorkspaceVolumeStatus status) {
        var volume = WorkspaceVolumeRecord.provisioning(workspace, 0, "ws-g0", "fake");
        volume.status = status;
        return volume;
    }

    private static final class FakeWorkspaces implements WorkspaceStore {
        private final Map<String, WorkspaceRecord> rows = new HashMap<>();
        private WorkspaceRecord stalePurgeCandidate;

        void put(WorkspaceRecord workspace) {
            rows.put(workspace.id, workspace);
        }

        boolean missing(String id) {
            return !rows.containsKey(id);
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
            return List.of();
        }

        @Override
        public List<WorkspaceRecord> listDeletedBefore(ZonedDateTime cutoff) {
            if (stalePurgeCandidate != null) return List.of(stalePurgeCandidate);
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
                workspace.errorCode = errorCode;
                workspace.errorMessage = errorMessage;
            }
        }

        @Override
        public void updateBootstrapState(String id, WorkspaceBootstrapState state) {
            var workspace = rows.get(id);
            if (workspace == null) return;
            workspace.bootstrapCompletedAt = state.bootstrapCompletedAt();
            workspace.bootstrapScriptHash = state.bootstrapScriptHash();
            workspace.startScriptHash = state.startScriptHash();
            workspace.lastBootstrapLog = state.lastBootstrapLog();
            workspace.envFilePresent = state.envFilePresent();
        }

        @Override
        public void touchLastUsed(String id, ZonedDateTime at) {
            var workspace = rows.get(id);
            if (workspace != null) workspace.lastUsedAt = at;
        }

        @Override
        public void touchWoken(String id, ZonedDateTime at) {
            var workspace = rows.get(id);
            if (workspace != null) workspace.lastWokenAt = at;
        }

        @Override
        public void touchSlept(String id, ZonedDateTime at) {
            var workspace = rows.get(id);
            if (workspace != null) workspace.lastSleptAt = at;
        }

        @Override
        public boolean softDelete(String id, ZonedDateTime at) {
            var workspace = rows.get(id);
            if (workspace == null || workspace.deletedAt != null) return false;
            workspace.deletedAt = at;
            return true;
        }

        @Override
        public boolean restore(String id) {
            var workspace = rows.get(id);
            if (workspace == null || workspace.deletedAt == null) return false;
            workspace.deletedAt = null;
            return true;
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

        WorkspaceVolumeStatus status(String workspaceId) {
            return rows.values().stream()
                    .filter(v -> v.workspaceId.equals(workspaceId))
                    .findFirst()
                    .orElseThrow()
                    .status;
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
            var binding = rows.get(workspaceId);
            if (binding == null || !sessionId.equals(binding.boundSessionId)) return false;
            binding.boundSessionId = null;
            return true;
        }

        @Override
        public boolean rebindSession(String workspaceId, String sandboxId, long runtimeGeneration, String sessionId, ZonedDateTime at) {
            var binding = rows.get(workspaceId);
            if (binding == null) return false;
            binding.boundSessionId = sessionId;
            return true;
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
            var lease = rows.get(workspaceId);
            if (lease == null || !lease.leaseToken.equals(leaseToken)) return false;
            lease.expiresAt = expiresAt;
            return true;
        }

        @Override
        public boolean release(String workspaceId, String leaseToken) {
            var lease = rows.get(workspaceId);
            if (lease == null || !lease.leaseToken.equals(leaseToken)) return false;
            rows.remove(workspaceId);
            return true;
        }

        @Override
        public int releaseBySession(String sessionId) {
            var removed = 0;
            for (var lease : new ArrayList<>(rows.values())) {
                if (sessionId.equals(lease.sessionId)) {
                    rows.remove(lease.workspaceId);
                    removed++;
                }
            }
            return removed;
        }

        @Override
        public int releaseByWorkspace(String workspaceId) {
            return rows.remove(workspaceId) != null ? 1 : 0;
        }
    }

    private static final class FakeEvents implements WorkspaceEventStore {
        private int count;

        @Override
        public void append(WorkspaceEventRecord event) {
            count++;
        }

        @Override
        public List<WorkspaceEventRecord> list(String workspaceId, int limit) {
            return List.of();
        }

        @Override
        public int deleteByWorkspace(String workspaceId) {
            var removed = count;
            count = 0;
            return removed;
        }
    }

    private static final class FakeUsage implements WorkspaceUsageStore {
        private final Map<String, WorkspaceUsageRecord> rows = new HashMap<>();

        @Override
        public Optional<WorkspaceUsageRecord> get(String workspaceId) {
            return Optional.ofNullable(rows.get(workspaceId));
        }

        @Override
        public Map<String, WorkspaceUsageRecord> listByWorkspaces(List<String> workspaceIds) {
            var result = new HashMap<String, WorkspaceUsageRecord>();
            for (var id : workspaceIds) {
                var row = rows.get(id);
                if (row != null) result.put(id, row);
            }
            return result;
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

    private static final class FakeProvisioner implements WorkspaceProvisioner {
        private final List<String> deleted = new ArrayList<>();
        private boolean fail;

        @Override
        public String provider() {
            return "fake";
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
            if (fail) {
                throw new WorkspaceProvisioningException(WorkspaceProvisioningException.PROVISION_FAILED, "injected failure");
            }
            deleted.add(volume.pvcName);
        }
    }
}
