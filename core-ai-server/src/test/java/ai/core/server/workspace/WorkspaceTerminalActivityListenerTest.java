package ai.core.server.workspace;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import ai.core.server.sandbox.SandboxTerminalRuntimeResolver;
import ai.core.server.sandbox.terminal.SandboxTerminalService;
import ai.core.server.session.ChatSessionQuery;
import ai.core.server.session.ChatSessionStore;
import ai.core.server.session.SessionActivityRegistry;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies interactive terminal activity keeps a session's persistent workspace awake, throttles
 * writes, and isolates failures without acquiring a turn lease.
 *
 * @author stephen
 */
class WorkspaceTerminalActivityListenerTest {

    @Test
    void boundSessionTouchesWorkspaceAndPreventsIdleSleep() {
        var config = enabledConfig();
        var workspaces = new StubWorkspaces();
        var bindings = new StubBindings();
        var sessions = new StubSessions();

        var workspace = readyIdleWorkspace(30);
        workspaces.put(workspace);
        var binding = WorkspaceBindingRecord.create(workspace.id, "ws-sandbox-1", "kubernetes", 1L);
        binding.runtimeStartedAt = ZonedDateTime.now().minusMinutes(40);
        bindings.upsert(binding);

        var session = new ChatSession();
        session.id = "session-1";
        session.workspaceId = workspace.id;
        sessions.put(session);

        var listener = new WorkspaceTerminalActivityListener(config, workspaces, sessions);
        var terminalService = newTerminalService(listener);

        var before = workspace.lastUsedAt;
        terminalService.recordActivity("session-1");

        assertEquals(1, workspaces.touchCalls.get());
        assertNotNull(workspace.lastUsedAt);
        assertTrue(workspace.lastUsedAt.isAfter(before));

        var runtimes = new FakeRuntimeManager();
        var sweeper = new WorkspaceSweeper(new WorkspaceSweeper.Deps(
                workspaces, bindings, new WorkspaceLeaseManager(new StubLeases(), config),
                new WorkspaceVolumeManager(new StubVolumes(), workspaces, null),
                new WorkspaceRuntimeLifecycle(workspaces, bindings, runtimes),
                new WorkspaceAudit(new StubEvents())), null);

        var result = sweeper.run(config, sessionId -> false);

        assertEquals(0, result.slept());
        assertEquals(0, runtimes.deletedCount.get());
    }

    @Test
    void secondActivityWithinThrottleWindowDoesNotWriteAgain() {
        var config = enabledConfig();
        var workspaces = new StubWorkspaces();
        var sessions = new StubSessions();
        var workspace = readyIdleWorkspace(30);
        workspaces.put(workspace);

        var session = new ChatSession();
        session.id = "session-1";
        session.workspaceId = workspace.id;
        sessions.put(session);

        var listener = new WorkspaceTerminalActivityListener(config, workspaces, sessions);
        var terminalService = newTerminalService(listener);

        terminalService.recordActivity("session-1");
        terminalService.recordActivity("session-1");

        assertEquals(1, sessions.getCalls.get());
        assertEquals(1, workspaces.touchCalls.get());
    }

    @Test
    void disabledWorkspaceFeatureIsSilentNoOp() {
        var config = new WorkspaceConfig();
        config.enabled = false;
        var workspaces = new StubWorkspaces();
        var sessions = new StubSessions();
        var session = new ChatSession();
        session.id = "session-1";
        session.workspaceId = "ws-1";
        sessions.put(session);

        var listener = new WorkspaceTerminalActivityListener(config, workspaces, sessions);
        assertDoesNotThrow(() -> listener.accept("session-1"));
        assertEquals(0, sessions.getCalls.get());
        assertEquals(0, workspaces.touchCalls.get());
    }

    @Test
    void sessionWithoutWorkspaceBindingIsSilentNoOp() {
        var config = enabledConfig();
        var workspaces = new StubWorkspaces();
        var sessions = new StubSessions();
        var session = new ChatSession();
        session.id = "session-ephemeral";
        sessions.put(session);

        var listener = new WorkspaceTerminalActivityListener(config, workspaces, sessions);
        assertDoesNotThrow(() -> listener.accept("session-ephemeral"));
        assertDoesNotThrow(() -> listener.accept("session-missing"));
        assertEquals(0, workspaces.touchCalls.get());
    }

    @Test
    void missingOrDeletedWorkspaceIsSilentNoOp() {
        var config = enabledConfig();
        var workspaces = new StubWorkspaces();
        var sessions = new StubSessions();

        var deleted = readyIdleWorkspace(30);
        deleted.deletedAt = ZonedDateTime.now().minusHours(1);
        workspaces.put(deleted);

        var sDeleted = new ChatSession();
        sDeleted.id = "session-deleted";
        sDeleted.workspaceId = deleted.id;
        sessions.put(sDeleted);

        var sMissing = new ChatSession();
        sMissing.id = "session-missing-ws";
        sMissing.workspaceId = "no-such-workspace";
        sessions.put(sMissing);

        var listener = new WorkspaceTerminalActivityListener(config, workspaces, sessions);
        assertDoesNotThrow(() -> listener.accept("session-deleted"));
        assertDoesNotThrow(() -> listener.accept("session-missing-ws"));
        assertEquals(0, workspaces.touchCalls.get());
    }

    @Test
    void storeExceptionsAreSwallowedAndThrottled() {
        var config = enabledConfig();
        var workspaces = new StubWorkspaces();
        var sessions = new StubSessions();
        sessions.failOnGet = true;

        var listener = new WorkspaceTerminalActivityListener(config, workspaces, sessions);
        var terminalService = newTerminalService(listener);

        assertDoesNotThrow(() -> terminalService.recordActivity("session-1"));
        assertDoesNotThrow(() -> terminalService.recordActivity("session-1"));
        assertEquals(1, sessions.getCalls.get());
        assertEquals(0, workspaces.touchCalls.get());

        sessions.failOnGet = false;
        var workspace = readyIdleWorkspace(30);
        workspaces.put(workspace);
        workspaces.failOnTouch = true;
        var session = new ChatSession();
        session.id = "session-2";
        session.workspaceId = workspace.id;
        sessions.put(session);

        assertDoesNotThrow(() -> terminalService.recordActivity("session-2"));
        assertDoesNotThrow(() -> terminalService.recordActivity("session-2"));
        assertEquals(1, workspaces.touchCalls.get());
    }

    private WorkspaceConfig enabledConfig() {
        var config = new WorkspaceConfig();
        config.enabled = true;
        config.minRuntimeSeconds = 300;
        return config;
    }

    private WorkspaceRecord readyIdleWorkspace(int idleMinutes) {
        var workspace = WorkspaceRecord.create("user-1", "dev", "agent-1");
        workspace.runtimeStatus = WorkspaceRuntimeStatus.READY;
        workspace.volumeStatus = WorkspaceVolumeStatus.READY;
        workspace.idleSleepMinutes = 15;
        workspace.runtimeGeneration = 1L;
        workspace.lastUsedAt = ZonedDateTime.now().minusMinutes(idleMinutes);
        return workspace;
    }

    private SandboxTerminalService newTerminalService(WorkspaceTerminalActivityListener listener) {
        var activityRegistry = new SessionActivityRegistry(null) {
            @Override
            public void touch(String sessionId) {
            }
        };
        var service = new SandboxTerminalService(
                (sessionId, userId) -> { },
                (sessionId, sandboxId, userId) -> new SandboxTerminalRuntimeResolver.TerminalRuntime(
                        SandboxTerminalRuntimeResolver.TerminalRuntimeStatus.CURRENT, "10.0.0.1", 8123),
                activityRegistry,
                sessionId -> { });
        service.activityListener(listener);
        return service;
    }

    private static final class FakeRuntimeManager extends WorkspaceRuntimeManager {
        final AtomicInteger deletedCount = new AtomicInteger();

        FakeRuntimeManager() {
            super(null, null, null);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void delete(String name) {
            deletedCount.incrementAndGet();
        }
    }

    private static final class StubSessions implements ChatSessionStore {
        final Map<String, ChatSession> rows = new HashMap<>();
        final AtomicInteger getCalls = new AtomicInteger();
        boolean failOnGet;

        void put(ChatSession session) {
            rows.put(session.id, session);
        }

        @Override
        public Optional<ChatSession> get(String id) {
            getCalls.incrementAndGet();
            if (failOnGet) throw new IllegalStateException("pg session store down");
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean insertIfAbsent(ChatSession session) {
            return false;
        }

        @Override
        public void upsert(ChatSession session) {
        }

        @Override
        public List<ChatSession> find(ChatSessionQuery query) {
            return List.of();
        }

        @Override
        public long count(ChatSessionQuery query) {
            return 0;
        }

        @Override
        public void recordComparison(String id, String comparisonId, String comparisonVariant, String comparisonPairId) {
        }

        @Override
        public void recordUserMessage(String id, String titleIfAbsent, ZonedDateTime now) {
        }

        @Override
        public void recordAgentMessage(String id, ZonedDateTime now) {
        }

        @Override
        public void addLoadedTools(String id, List<ToolRef> refs) {
        }

        @Override
        public void addLoadedSkillIds(String id, List<String> skillIds) {
        }

        @Override
        public void addLoadedSubAgentIds(String id, List<String> agentIds) {
        }

        @Override
        public void removeLoadedSkillIds(String id, List<String> skillIds) {
        }

        @Override
        public void appendArtifact(String id, AgentRunArtifact artifact) {
        }

        @Override
        public void updateTitle(String id, String title) {
        }

        @Override
        public void updateNotifyOnComplete(String id, boolean enabled) {
        }

        @Override
        public boolean softDelete(String id, ZonedDateTime at) {
            return false;
        }
    }

    private static final class StubWorkspaces implements WorkspaceStore {
        final Map<String, WorkspaceRecord> rows = new HashMap<>();
        final AtomicInteger touchCalls = new AtomicInteger();
        boolean failOnTouch;

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
            return List.of();
        }

        @Override
        public boolean insertIfAbsent(WorkspaceRecord workspace) {
            return false;
        }

        @Override
        public void update(WorkspaceRecord workspace) {
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
            touchCalls.incrementAndGet();
            if (failOnTouch) throw new IllegalStateException("pg workspace store down");
            var workspace = rows.get(id);
            if (workspace != null) workspace.lastUsedAt = at;
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

    private static final class StubBindings implements WorkspaceBindingStore {
        final Map<String, WorkspaceBindingRecord> rows = new HashMap<>();

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

    private static final class StubVolumes implements WorkspaceVolumeStore {
        @Override
        public Optional<WorkspaceVolumeRecord> get(String workspaceId, long volumeGeneration) {
            return Optional.empty();
        }

        @Override
        public Optional<WorkspaceVolumeRecord> active(String workspaceId) {
            return Optional.empty();
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
            return false;
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

    private static final class StubLeases implements WorkspaceLeaseStore {
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
            return false;
        }

        @Override
        public boolean renew(String workspaceId, String leaseToken, ZonedDateTime expiresAt) {
            return false;
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

    private static final class StubEvents implements WorkspaceEventStore {
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
