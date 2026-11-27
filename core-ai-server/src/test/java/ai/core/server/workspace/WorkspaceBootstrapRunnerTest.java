package ai.core.server.workspace;

import ai.core.server.sandbox.agentsandbox.AgentSandboxClient;
import ai.core.tool.ToolCallResult;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceBootstrapRunnerTest {

    @Test
    void runsBootstrapOnceViaAsyncPollAndDoesNotAutoRerunWhenScriptHashChanges() {
        var workspace = WorkspaceRecord.create("user-1", "dev", "agent-1");
        workspace.bootstrapScript = "echo bootstrap-v1";
        workspace.startScript = "echo start-v1";

        var asyncCommands = new ArrayList<String>();
        var runner = new WorkspaceBootstrapRunner(host -> new FakeRuntimeClient(asyncCommands, false, false, true), 1800, 600, 10L);

        var first = runner.prepare(workspace, "10.0.0.1", false);
        assertNotNull(first.bootstrapCompletedAt());
        assertEquals(WorkspaceBootstrapRunner.sha256Hex("echo bootstrap-v1"), first.bootstrapScriptHash());
        assertEquals(WorkspaceBootstrapRunner.sha256Hex("echo start-v1"), first.startScriptHash());
        assertTrue(first.envFilePresent());
        assertTrue(first.lastBootstrapLog().contains("/workspace/.core-ai/logs/"));
        assertEquals(2, asyncCommands.size());

        workspace.bootstrapCompletedAt = first.bootstrapCompletedAt();
        workspace.bootstrapScriptHash = first.bootstrapScriptHash();
        workspace.bootstrapScript = "echo bootstrap-v2";
        assertTrue(WorkspaceBootstrapRunner.scriptChanged(workspace.bootstrapScript, workspace.bootstrapScriptHash));

        asyncCommands.clear();
        var second = runner.prepare(workspace, "10.0.0.1", false);
        assertEquals(1, asyncCommands.size());
        assertTrue(asyncCommands.getFirst().contains("start.sh"));
        assertEquals(first.bootstrapScriptHash(), second.bootstrapScriptHash());
    }

    @Test
    void failingBootstrapThrowsAndPreservesLogSnippet() {
        var workspace = WorkspaceRecord.create("user-1", "dev", "agent-1");
        workspace.bootstrapScript = "exit 2";
        var runner = new WorkspaceBootstrapRunner(host -> new FakeRuntimeClient(new ArrayList<>(), true, false, false), 1800, 600, 10L);

        var failure = assertThrows(WorkspaceBootstrapRunner.BootstrapFailure.class,
                () -> runner.prepare(workspace, "10.0.0.1", false));

        assertEquals(WorkspaceException.BOOTSTRAP_FAILED, failure.errorCode);
        assertNull(failure.state.bootstrapCompletedAt());
        assertTrue(failure.state.lastBootstrapLog().contains("bootstrap boom"));
        assertFalse(failure.state.envFilePresent());
    }

    @Test
    void wrapperRedirectsStderrAndRotatesToLatestTwentyLogs() {
        var cmd = WorkspaceBootstrapRunner.buildScriptWrapper("bootstrap",
                "/workspace/.core-ai/bootstrap.sh", "/workspace/.core-ai/logs/bootstrap-20261128-000000.log");

        assertTrue(cmd.contains(">> '/workspace/.core-ai/logs/bootstrap-20261128-000000.log' 2>&1"));
        assertTrue(cmd.contains("tail -n +21 | xargs -r rm -f"));
        assertTrue(cmd.contains("CORE_AI_EXIT_CODE="));
    }

    @Test
    void templatesCommandGuardsAgainstOverwritingExistingFiles() {
        var cmd = WorkspaceTemplates.buildEnsureCommand();

        assertTrue(cmd.contains("[ ! -e /workspace/AGENTS.md ]"));
        assertTrue(cmd.contains("[ ! -e /workspace/.core-ai/NOTES.md ]"));
    }

    @Test
    void failingStartBlocksReadyRejectsToolCallsAndRecoversOnRetryWithoutRecreatingVolume() {
        var workspaces = new InMemoryWorkspaces();
        var volumes = new InMemoryVolumes();
        var bindings = new InMemoryBindings();
        var leases = new InMemoryLeases();
        var events = new InMemoryEvents();
        var usage = new InMemoryUsage();
        var provisioner = new InMemoryProvisioner();
        var runtimes = new FakeRuntimeManager();

        var failStart = new AtomicInteger(1);
        var runner = new WorkspaceBootstrapRunner(
                host -> new FakeRuntimeClient(new ArrayList<>(), false, failStart.get() > 0, true), 1800, 600, 10L);
        var config = new WorkspaceConfig();
        config.enabled = true;
        var service = new WorkspaceService(
                new WorkspaceStores(workspaces, volumes, bindings, events, leases, usage),
                new WorkspaceService.RuntimeDeps(provisioner, runtimes, null, runner), config, Runnable::run);

        var created = service.create("user-1", new WorkspaceCreateSpec("ws-err", "agent-1", 16, null, 15,
                "echo boot", "exit 1"));
        var volGen = created.activeVolumeGeneration;

        var wakeErr = assertThrows(WorkspaceException.class, () -> service.wake("user-1", created.id));
        assertEquals(WorkspaceException.START_FAILED, wakeErr.errorCode);
        var failed = service.get("user-1", created.id);
        assertEquals(WorkspaceRuntimeStatus.ERROR, failed.runtimeStatus);
        assertEquals(WorkspaceException.START_FAILED, failed.errorCode);
        assertEquals(volGen, failed.activeVolumeGeneration);
        assertNotNull(failed.lastBootstrapLog);

        var toolErr = assertThrows(WorkspaceException.class,
                () -> service.beginTurn("user-1", created.id, "session-1", true));
        assertEquals(WorkspaceException.START_FAILED, toolErr.errorCode);

        failStart.set(0);
        service.update("user-1", created.id, null, null, null, "echo fixed");
        var recovered = service.retry("user-1", created.id, false);
        assertEquals(WorkspaceRuntimeStatus.READY, recovered.runtimeStatus);
        assertNull(recovered.errorCode);
        assertEquals(volGen, recovered.activeVolumeGeneration);
        assertNotNull(recovered.lastWokenAt);
    }

    private static final class FakeRuntimeClient implements WorkspaceBootstrapRunner.RuntimeClient {
        private final List<String> asyncCommands;
        private final boolean failBootstrap;
        private final boolean failStart;
        private final boolean envPresent;
        private final Map<String, ToolCallResult> tasks = new HashMap<>();
        private final Map<String, Integer> polls = new HashMap<>();
        private int seq;

        private FakeRuntimeClient(List<String> asyncCommands, boolean failBootstrap, boolean failStart, boolean envPresent) {
            this.asyncCommands = asyncCommands;
            this.failBootstrap = failBootstrap;
            this.failStart = failStart;
            this.envPresent = envPresent;
        }

        @Override
        public ToolCallResult execute(String toolName, String arguments) {
            if (arguments.contains("\"run_in_background\":true")) {
                asyncCommands.add(arguments);
                var rawId = "task-" + (++seq);
                var isBootstrap = arguments.contains("bootstrap.sh");
                var shouldFail = isBootstrap ? failBootstrap : failStart;
                var logLine = shouldFail ? (isBootstrap ? "bootstrap boom" : "start boom") : "ok";
                var logPath = "/workspace/.core-ai/logs/" + (isBootstrap ? "bootstrap" : "start") + "-test.log";
                var output = "CORE_AI_EXIT_CODE=" + (shouldFail ? 1 : 0) + "\n"
                        + "CORE_AI_LOG_FILE=" + logPath + "\n"
                        + "---CORE_AI_LOG_TAIL---\n" + logLine + "\n";
                tasks.put(rawId, ToolCallResult.completed(output));
                polls.put(rawId, 0);
                return ToolCallResult.pending("st-" + rawId, "submitted")
                        .withStats("sandboxTaskId", rawId);
            }
            if (arguments.contains("/workspace/.core-ai/env")) {
                return ToolCallResult.completed(envPresent ? "PRESENT\n" : "ABSENT\n");
            }
            return ToolCallResult.completed("");
        }

        @Override
        public ToolCallResult pollTask(String taskId) {
            var count = polls.merge(taskId, 1, Integer::sum);
            if (count == 1) return ToolCallResult.pending(taskId, "running");
            return tasks.getOrDefault(taskId, ToolCallResult.failed("not found"));
        }

        @Override
        public ToolCallResult cancelTask(String taskId) {
            return ToolCallResult.failed("CANCELLED");
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeRuntimeManager extends WorkspaceRuntimeManager {
        private final Map<String, String> hosts = new HashMap<>();

        private FakeRuntimeManager() {
            super(null, null, null);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String create(WorkspaceRuntimeSpec spec) {
            var name = name(spec);
            hosts.put(name, "10.0.0.99");
            return name;
        }

        @Override
        public void waitReady(String name, int timeoutSeconds) {
        }

        @Override
        public void delete(String name) {
            hosts.remove(name);
        }

        @Override
        public Optional<AgentSandboxClient.SandboxCR> get(String name) {
            return hosts.containsKey(name) ? Optional.of(new AgentSandboxClient.SandboxCR()) : Optional.empty();
        }

        @Override
        public Optional<String> endpointHost(String name) {
            return Optional.ofNullable(hosts.get(name));
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
            return Optional.empty();
        }

        @Override
        public Optional<WorkspaceRecord> findByName(String ownerUserId, String name) {
            return Optional.empty();
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId) {
            return listByOwner(ownerUserId, false);
        }

        @Override
        public List<WorkspaceRecord> listByOwner(String ownerUserId, boolean includeDeleted) {
            return rows.values().stream()
                    .filter(w -> w.ownerUserId.equals(ownerUserId) && (includeDeleted || !w.deleted()))
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
            return rows.putIfAbsent(workspace.id, workspace) == null;
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

    private static final class InMemoryVolumes implements WorkspaceVolumeStore {
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
            return rows.values().stream().filter(v -> v.workspaceId.equals(workspaceId)).toList();
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
            rows.put(volume.workspaceId + ":" + volume.volumeGeneration, volume);
        }

        @Override
        public void updateStatus(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
            var v = rows.get(workspaceId + ":" + volumeGeneration);
            if (v != null) v.status = status;
        }

        @Override
        public void markDeleted(String workspaceId, long volumeGeneration, WorkspaceVolumeStatus status) {
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

    private static final class InMemoryLeases implements WorkspaceLeaseStore {
        @Override
        public Optional<WorkspaceLeaseRecord> get(String workspaceId) {
            return Optional.empty();
        }

        @Override
        public boolean insert(WorkspaceLeaseRecord lease) {
            return true;
        }

        @Override
        public boolean replace(String workspaceId, String expectedToken, WorkspaceLeaseRecord lease) {
            return true;
        }

        @Override
        public boolean renew(String workspaceId, String leaseToken, ZonedDateTime expiresAt) {
            return true;
        }

        @Override
        public boolean release(String workspaceId, String leaseToken) {
            return true;
        }

        @Override
        public int releaseBySession(String sessionId) {
            return 0;
        }

        @Override
        public int releaseByWorkspace(String workspaceId) {
            return 0;
        }
    }

    private static final class InMemoryEvents implements WorkspaceEventStore {
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

    private static final class InMemoryUsage implements WorkspaceUsageStore {
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

    private static final class InMemoryProvisioner implements WorkspaceProvisioner {
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
            return new VolumeHandle(volumeName(workspace.id, volumeGeneration), "Bound");
        }

        @Override
        public void deleteVolume(WorkspaceVolumeRecord volume) {
        }
    }
}
