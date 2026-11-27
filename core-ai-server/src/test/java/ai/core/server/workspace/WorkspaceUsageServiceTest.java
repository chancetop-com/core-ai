package ai.core.server.workspace;

import ai.core.sandbox.SandboxConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceUsageServiceTest {
    private static final long GIB = 1024L * 1024 * 1024;

    private final Map<String, WorkspaceRecord> workspaceRows = new HashMap<>();
    private final Map<String, WorkspaceBindingRecord> bindingRows = new HashMap<>();
    private final Map<String, WorkspaceUsageRecord> usageRows = new HashMap<>();
    private final Map<String, WorkspaceLeaseRecord> leaseRows = new HashMap<>();
    private final Map<String, String> endpoints = new HashMap<>();
    private final AtomicInteger statCalls = new AtomicInteger();
    private final List<Runnable> scheduledWakeTasks = new ArrayList<>();

    private WorkspaceUsageService.RuntimeStatResponse nextStat;
    private RuntimeException statFailure;
    private WorkspaceUsageService usageService;
    private WorkspaceService workspaceService;
    private WorkspaceConfig config;

    @BeforeEach
    void setUp() {
        config = new WorkspaceConfig();
        config.enabled = true;
        config.usageSampleMinutes = 10;

        nextStat = new WorkspaceUsageService.RuntimeStatResponse();
        nextStat.capacityBytes = 32 * GIB;
        nextStat.usedBytes = 12 * GIB;
        nextStat.availableBytes = 20 * GIB;
        nextStat.inodesTotal = 2_000_000L;
        nextStat.inodesUsed = 45_000L;

        var workspaces = new MapWorkspaceStore(workspaceRows);
        var volumes = new MapVolumeStore();
        var bindings = new MapBindingStore(bindingRows);
        var events = new NoopEventStore();
        var leases = new MapLeaseStore(leaseRows);
        var usageStore = new MapUsageStore(usageRows);
        var stores = new WorkspaceStores(workspaces, volumes, bindings, events, leases, usageStore);
        var runtimes = new StubRuntimeManager(endpoints);

        usageService = new WorkspaceUsageService(workspaces, bindings, usageStore, runtimes, (host, port) -> {
            assertEquals(SandboxConstants.RUNTIME_PORT, port);
            statCalls.incrementAndGet();
            if (statFailure != null) throw statFailure;
            return nextStat;
        });
        workspaceService = new WorkspaceService(stores, new StubProvisioner(), runtimes, usageService,
                config, scheduledWakeTasks::add);
    }

    @Test
    void samplePersistsMetricsWithoutAcquiringLease() {
        var workspace = readyWorkspace("ws-1", "sb-1", "10.0.0.5");

        assertTrue(usageService.sample(workspace.id));
        assertEquals(1, statCalls.get());
        assertTrue(leaseRows.isEmpty());

        var cached = usageService.read(workspace.id).orElseThrow();
        assertEquals(12 * GIB, cached.usedBytes);
        assertEquals(32 * GIB, cached.capacityBytes);
        assertEquals(20 * GIB, cached.availableBytes);
        assertEquals(45_000L, cached.inodesUsed);
        assertEquals(2_000_000L, cached.inodesTotal);
        assertEquals(2L, cached.volumeGeneration);
        assertNotNull(cached.sampledAt);
    }

    @Test
    void sampleSwallowsRuntimeErrorsAndNeverThrows() {
        var workspace = readyWorkspace("ws-1", "sb-1", "10.0.0.5");
        statFailure = new RuntimeException("connection refused");

        assertFalse(usageService.sample(workspace.id));
        assertTrue(usageService.read(workspace.id).isEmpty());
    }

    @Test
    void sampleSkipsNonReadyWorkspaces() {
        var workspace = readyWorkspace("ws-1", "sb-1", "10.0.0.5");
        workspace.runtimeStatus = WorkspaceRuntimeStatus.NONE;

        assertFalse(usageService.sample(workspace.id));
        assertEquals(0, statCalls.get());
    }

    @Test
    void sweepSamplesOnlyStaleOrMissingReadyWorkspacesWithoutLease() {
        var missing = readyWorkspace("ws-missing", "sb-1", "10.0.0.1");
        var stale = readyWorkspace("ws-stale", "sb-2", "10.0.0.2");
        var fresh = readyWorkspace("ws-fresh", "sb-3", "10.0.0.3");
        putSample(stale.id, ZonedDateTime.now().minusMinutes(15));
        putSample(fresh.id, ZonedDateTime.now().minusMinutes(2));

        var result = workspaceService.runLifecycleSweep();

        assertEquals(2, result.usageSampled());
        assertEquals(2, statCalls.get());
        assertTrue(usageRows.containsKey(missing.id));
        assertTrue(usageRows.get(stale.id).sampledAt.isAfter(ZonedDateTime.now().minusMinutes(1)));
        assertTrue(leaseRows.isEmpty());
    }

    @Test
    void sweepSkipsSamplingWhenDisabledByZeroInterval() {
        readyWorkspace("ws-1", "sb-1", "10.0.0.1");
        config.usageSampleMinutes = 0;

        var result = workspaceService.runLifecycleSweep();

        assertEquals(0, result.usageSampled());
        assertEquals(0, statCalls.get());
    }

    @Test
    void wakeSchedulesUsageSamplingAsynchronouslyWithoutBlockingReady() {
        var workspace = WorkspaceRecord.create("user-1", "dev", "agent-1");
        workspace.volumeStatus = WorkspaceVolumeStatus.READY;
        workspace.activeVolumeGeneration = 1L;
        workspaceRows.put(workspace.id, workspace);

        var woken = workspaceService.wake("user-1", workspace.id);

        assertEquals(WorkspaceRuntimeStatus.READY, woken.runtimeStatus);
        assertEquals(1, scheduledWakeTasks.size());
        assertEquals(0, statCalls.get());

        var binding = bindingRows.get(workspace.id);
        endpoints.put(binding.sandboxId, "10.0.0.9");
        scheduledWakeTasks.getFirst().run();

        assertEquals(1, statCalls.get());
        assertTrue(usageService.read(workspace.id).isPresent());
    }

    @Test
    void usageReadEnforcesWorkspaceOwnership() {
        var workspace = readyWorkspace("ws-1", "sb-1", "10.0.0.5");
        putSample(workspace.id, ZonedDateTime.now());

        assertTrue(workspaceService.usage("user-1", workspace.id).isPresent());
        var error = assertThrows(WorkspaceException.class, () -> workspaceService.usage("user-2", workspace.id));
        assertEquals(WorkspaceException.NOT_FOUND, error.errorCode);
    }

    private WorkspaceRecord readyWorkspace(String id, String sandboxId, String host) {
        var workspace = WorkspaceRecord.create("user-1", id, "agent-1");
        workspace.id = id;
        workspace.volumeStatus = WorkspaceVolumeStatus.READY;
        workspace.runtimeStatus = WorkspaceRuntimeStatus.READY;
        workspace.activeVolumeGeneration = 2L;
        workspace.lastUsedAt = ZonedDateTime.now();
        workspaceRows.put(id, workspace);
        var binding = WorkspaceBindingRecord.create(id, sandboxId, "fake", 1L);
        binding.runtimeStartedAt = ZonedDateTime.now();
        bindingRows.put(id, binding);
        endpoints.put(sandboxId, host);
        return workspace;
    }

    private void putSample(String workspaceId, ZonedDateTime sampledAt) {
        var record = new WorkspaceUsageRecord();
        record.workspaceId = workspaceId;
        record.usedBytes = GIB;
        record.capacityBytes = 32 * GIB;
        record.sampledAt = sampledAt;
        usageRows.put(workspaceId, record);
    }

    private static final class StubRuntimeManager extends WorkspaceRuntimeManager {
        private final Map<String, String> endpoints;

        StubRuntimeManager(Map<String, String> endpoints) {
            super(null, null, null);
            this.endpoints = endpoints;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String name(WorkspaceRuntimeSpec spec) {
            return "ws-" + spec.workspaceId + "-r" + spec.runtimeGeneration;
        }

        @Override
        public String create(WorkspaceRuntimeSpec spec) {
            return name(spec);
        }

        @Override
        public void waitReady(String name, int timeoutSeconds) {
        }

        @Override
        public Optional<String> endpointHost(String name) {
            return Optional.ofNullable(endpoints.get(name));
        }
    }

    private static final class StubProvisioner implements WorkspaceProvisioner {
        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String volumeName(String workspaceId, long volumeGeneration) {
            return "pvc-" + workspaceId + "-" + volumeGeneration;
        }

        @Override
        public VolumeHandle ensureVolume(WorkspaceRecord workspace, long volumeGeneration) {
            return new VolumeHandle(volumeName(workspace.id, volumeGeneration), "Bound");
        }

        @Override
        public void deleteVolume(WorkspaceVolumeRecord volume) {
        }
    }

    private static final class MapWorkspaceStore implements WorkspaceStore {
        private final Map<String, WorkspaceRecord> rows;

        MapWorkspaceStore(Map<String, WorkspaceRecord> rows) {
            this.rows = rows;
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
            return rows.values().stream().filter(w -> ownerUserId.equals(w.ownerUserId) && !w.deleted()).toList();
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId, boolean includeDeleted) {
            return listByOwner(ownerUserId);
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
            return rows.putIfAbsent(workspace.id, workspace) == null;
        }

        @Override
        public void update(WorkspaceRecord workspace) {
            rows.put(workspace.id, workspace);
        }

        @Override
        public void updateVolumeState(String id, WorkspaceVolumeStatus status, Long activeVolumeGeneration,
                                      String errorCode, String errorMessage) {
            var row = rows.get(id);
            if (row != null) {
                row.volumeStatus = status;
                row.activeVolumeGeneration = activeVolumeGeneration;
            }
        }

        @Override
        public void updateRuntimeState(String id, WorkspaceRuntimeStatus status, long runtimeGeneration,
                                       String errorCode, String errorMessage) {
            var row = rows.get(id);
            if (row != null) {
                row.runtimeStatus = status;
                row.runtimeGeneration = runtimeGeneration;
            }
        }

        @Override
        public void touchLastUsed(String id, ZonedDateTime at) {
            var row = rows.get(id);
            if (row != null) row.lastUsedAt = at;
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

    private static final class MapVolumeStore implements WorkspaceVolumeStore {
        private final Map<String, WorkspaceVolumeRecord> rows = new HashMap<>();

        @Override
        public Optional<WorkspaceVolumeRecord> get(String workspaceId, long volumeGeneration) {
            return Optional.ofNullable(rows.get(workspaceId + ":" + volumeGeneration));
        }

        @Override
        public Optional<WorkspaceVolumeRecord> active(String workspaceId) {
            return rows.values().stream().filter(v -> v.workspaceId.equals(workspaceId)).findFirst();
        }

        @Override
        public List<WorkspaceVolumeRecord> listByWorkspace(String workspaceId) {
            return List.of();
        }

        @Override
        public List<WorkspaceVolumeRecord> listByStatus(WorkspaceVolumeStatus status) {
            return List.of();
        }

        @Override
        public boolean insertIfAbsent(WorkspaceVolumeRecord volume) {
            rows.put(volume.workspaceId + ":" + volume.volumeGeneration, volume);
            return true;
        }

        @Override
        public void update(WorkspaceVolumeRecord volume) {
        }

        @Override
        public void updateStatus(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
        }

        @Override
        public void markDeleted(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
        }
    }

    private static final class MapBindingStore implements WorkspaceBindingStore {
        private final Map<String, WorkspaceBindingRecord> rows;

        MapBindingStore(Map<String, WorkspaceBindingRecord> rows) {
            this.rows = rows;
        }

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

    private static final class MapLeaseStore implements WorkspaceLeaseStore {
        private final Map<String, WorkspaceLeaseRecord> rows;

        MapLeaseStore(Map<String, WorkspaceLeaseRecord> rows) {
            this.rows = rows;
        }

        @Override
        public Optional<WorkspaceLeaseRecord> get(String workspaceId) {
            return Optional.ofNullable(rows.get(workspaceId));
        }

        @Override
        public boolean insert(WorkspaceLeaseRecord lease) {
            return rows.putIfAbsent(lease.workspaceId, lease) == null;
        }

        @Override
        public boolean replace(String workspaceId, String expectedToken, WorkspaceLeaseRecord lease) {
            rows.put(workspaceId, lease);
            return true;
        }

        @Override
        public boolean renew(String workspaceId, String leaseToken, ZonedDateTime expiresAt) {
            return false;
        }

        @Override
        public boolean release(String workspaceId, String leaseToken) {
            return rows.remove(workspaceId) != null;
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

    private static final class MapUsageStore implements WorkspaceUsageStore {
        private final Map<String, WorkspaceUsageRecord> rows;

        MapUsageStore(Map<String, WorkspaceUsageRecord> rows) {
            this.rows = rows;
        }

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

    private static final class NoopEventStore implements WorkspaceEventStore {
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
}
