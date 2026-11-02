package ai.core.cli.appserver;

import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.CustomEvent;
import ai.core.agent.Agent;
import ai.core.agent.AttachedContent;
import ai.core.cli.CliAppHelper;
import ai.core.cli.agent.CliAgent;
import ai.core.cli.helper.HelperConfigStore;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemoryExtractionReport;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.llm.LLMProviderType;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionLoad;
import ai.core.session.SessionLoads;
import ai.core.session.SessionLock;
import ai.core.session.SessionPersistence;
import ai.core.cli.upgrade.VersionUtil;
import ai.core.session.ToolPermissionStore;
import ai.core.session.transcript.TranscriptMetadata;
import ai.core.session.transcript.TranscriptRecorder;
import ai.core.session.transcript.TranscriptStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Live engine sessions: creation (agent wiring identical to the CLI REPL minus the terminal),
 * listing / resume / delete, history pagination and the per-session operations the desktop calls.
 *
 * @author stephen
 */
public class EngineSessionRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineSessionRegistry.class);
    private static final NotificationSink NOOP_SINK = (method, params) -> { };
    private static final Function<String, String> NO_INTERACTIVE_USER =
            question -> "(no interactive user is attached to this session; proceed with best judgment)";
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;
    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int MAX_HISTORY_LIMIT = 500;

    private static RpcException sessionNotFound(String id) {
        return RpcException.business("SESSION_NOT_FOUND", "session not found: " + id, "sessionId", id);
    }

    private final EngineBootstrap bootstrap;
    private final Supplier<NotificationSink> sinkSupplier;
    private final SessionTitleStore titles;
    private final EngineSessionModels models;
    private final MdMemoryProvider memoryProvider;
    private final Map<String, EngineSession> sessions = new ConcurrentHashMap<>();
    private final SessionTitleResolver titleResolver;
    private final SessionLifecycle lifecycle;
    private volatile String approvalPolicy = "ask";
    private volatile String clientType = "cli";
    private volatile WorkspaceAutoPermissionStore workspaceAutoStore;
    private volatile EngineScheduler scheduler;

    public EngineSessionRegistry(EngineBootstrap bootstrap, Supplier<NotificationSink> sinkSupplier) {
        this.bootstrap = bootstrap;
        this.sinkSupplier = sinkSupplier;
        this.titles = new SessionTitleStore(bootstrap.sessionsDir);
        this.models = new EngineSessionModels(bootstrap.result.llmProviders, bootstrap.sessionsDir);
        this.titleResolver = new SessionTitleResolver(titles, bootstrap.sessionManager, sessions);
        var sidecars = new SessionSidecars(titles, models, titleResolver);
        this.lifecycle = new SessionLifecycle(bootstrap, sessions, sidecars, () -> scheduler, sinkSupplier);
        this.memoryProvider = bootstrap.memoryEnabled ? new MdMemoryProvider(bootstrap.workspace) : null;
        if (bootstrap.memoryEnabled) {
            MemoryTriggerService.getInstance(bootstrap.workspace).addActivityListener(this::publishMemoryActivity);
        }
        SessionRetention.retain(bootstrap, titles, models);
    }

    public void setApprovalPolicy(String policy) {
        this.approvalPolicy = policy;
    }

    /** Trace origin declared by the connected client; sessions built afterwards upload under it. */
    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    void setScheduler(EngineScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public ObjectNode list(ObjectNode params) {
        int offset = Math.max(0, Params.intParam(params, "cursor", 0));
        int limit = Math.max(1, Math.min(Params.intParam(params, "limit", DEFAULT_LIST_LIMIT), MAX_LIST_LIMIT));
        var all = bootstrap.sessionManager.listSessions();
        int end = Math.min(offset + limit, all.size());
        var page = offset < end ? all.subList(offset, end) : List.<SessionPersistence.SessionInfo>of();
        var node = Params.object();
        var array = node.putArray("sessions");
        for (var info : page) {
            array.add(titleResolver.summary(info));
        }
        if (end < all.size()) {
            node.put("nextCursor", String.valueOf(end));
        }
        return node;
    }

    public ObjectNode create(ObjectNode params) {
        var title = Params.optionalText(params, "title");
        for (int attempt = 0; attempt < 3; attempt++) {
            var id = CliAppHelper.defaultSessionId("cli-");
            var acquire = SessionLock.tryAcquire(bootstrap.sessionsDir, id, "engine");
            if (!(acquire instanceof SessionLock.AcquireResult.Acquired acquired)) continue;
            var lock = acquired.lock();
            EngineSession session;
            try {
                session = build(id, false, lock);
            } catch (RuntimeException e) {
                lock.close();
                throw e;
            }
            if (sessions.putIfAbsent(id, session) == null) {
                if (title != null) {
                    titles.set(id, title.strip());
                }
                var node = Params.object();
                node.put("sessionId", id);
                return node;
            }
            session.close();
        }
        throw RpcException.business("SESSION_ID_EXHAUSTED", "could not allocate a unique session id");
    }

    public ObjectNode resume(ObjectNode params) {
        var id = SessionIds.check(Params.requiredText(params, "sessionId"));
        var session = resumeSession(id);
        var node = Params.object();
        node.put("sessionId", id);
        node.put("status", session.running() ? "running" : "idle");
        node.put("messageCount", session.agent().getHistory().size());
        return node;
    }

    public ObjectNode close(ObjectNode params) {
        return lifecycle.close(params);
    }

    private EngineSession resumeSession(String id) {
        var existing = sessions.get(id);
        if (existing != null) {
            existing.touch();
            return existing;
        }
        if (!bootstrap.sessionPersistence.exists(id)) throw sessionNotFound(id);
        var acquire = SessionLock.tryAcquire(bootstrap.sessionsDir, id, "engine");
        if (acquire instanceof SessionLock.AcquireResult.Busy busy) throw SessionLifecycle.sessionBusy(busy);
        var lock = ((SessionLock.AcquireResult.Acquired) acquire).lock();
        try {
            var session = build(id, true, lock);
            var raced = sessions.putIfAbsent(id, session);
            if (raced != null) {
                session.close();
                raced.touch();
                return raced;
            }
            return session;
        } catch (RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    void evictIdle() {
        lifecycle.evictIdle();
    }

    public ObjectNode save(ObjectNode params) {
        var session = requireSession(params);
        var node = Params.object();
        node.put("sessionId", session.id());
        node.put("persisted", session.save());
        return node;
    }

    /** Used by the scheduler: rebuild a persisted session quietly, never create a new one. */
    EngineSession resumeQuietly(String sessionId) {
        if (!sessionFileExists(sessionId)) return null;
        try {
            return resumeSession(sessionId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to resume session quietly, sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }

    public ObjectNode records(ObjectNode params) {
        return requireSession(params).records(params);
    }

    public ObjectNode recordPayload(ObjectNode params) {
        return requireSession(params).recordPayload(params);
    }

    public ObjectNode history(ObjectNode params) {
        var session = requireSession(params);
        int limit = Math.max(1, Math.min(Params.intParam(params, "limit", DEFAULT_HISTORY_LIMIT), MAX_HISTORY_LIMIT));
        var history = session.agent().getHistory();
        int total = history.size();
        var cursor = Params.optionalInt(params, "cursor");
        int end = cursor == null ? total : Math.max(0, Math.min(cursor, total));
        int begin = Math.max(0, end - limit);
        var node = Params.object();
        var array = node.putArray("messages");
        for (var message : history.subList(begin, end)) {
            var text = message.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            var item = array.addObject();
            item.put("role", message.role.name().toLowerCase(Locale.ROOT));
            item.put("text", text);
        }
        node.put("total", total);
        if (begin > 0) {
            node.put("nextCursor", String.valueOf(begin));
        }
        return node;
    }

    public ObjectNode send(ObjectNode params) {
        var session = requireSession(params);
        var parsed = SendParts.parse(params, bootstrap.workspace, SessionLifecycle.stagingDir(session.id()));
        if (!parsed.hasContent()) {
            throw RpcException.invalidParams("message must contain text or attachments");
        }
        boolean queued = session.running();
        List<AttachedContent> attachments = parsed.attachments().isEmpty() ? null : parsed.attachments();
        session.inProcess().sendMessage(parsed.text(), null, attachments, Params.optionalText(params, "turnId"));
        var node = Params.object();
        node.put("accepted", true);
        node.put("queued", queued);
        return node;
    }

    public ObjectNode cancel(ObjectNode params) {
        var session = requireSession(params);
        boolean running = session.running();
        session.inProcess().cancelTurn();
        var node = Params.object();
        node.put("cancelled", running);
        return node;
    }

    public ObjectNode approve(ObjectNode params) {
        var session = requireSession(params);
        var callId = Params.requiredText(params, "callId");
        var decisionText = Params.requiredText(params, "decision");
        ApprovalDecision decision;
        try {
            decision = ApprovalDecision.valueOf(decisionText);
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams("invalid decision: " + decisionText, e);
        }
        session.inProcess().approveToolCall(callId, decision);
        return Params.object();
    }

    public ObjectNode rename(ObjectNode params) {
        var id = SessionIds.check(Params.requiredText(params, "sessionId"));
        if (!sessions.containsKey(id) && !bootstrap.sessionPersistence.exists(id)) {
            throw sessionNotFound(id);
        }
        titles.set(id, Params.requiredText(params, "title").strip());
        titleResolver.invalidate(id);
        var node = Params.object();
        node.put("sessionId", id);
        return node;
    }

    public ObjectNode delete(ObjectNode params) {
        return lifecycle.delete(params);
    }

    public ObjectNode export(ObjectNode params) {
        return requireSession(params).exportMarkdown();
    }

    public ObjectNode stats(ObjectNode params) {
        return requireSession(params).stats();
    }

    public ObjectNode compact(ObjectNode params) {
        return requireSession(params).compact();
    }

    public ObjectNode undo(ObjectNode params) {
        return requireSession(params).undo();
    }

    public EngineSession find(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    public EngineSession any() {
        return sessions.values().stream().findFirst().orElse(null);
    }

    /** Applies a model switch to one session (or all live sessions) and returns the affected ids. */
    public List<String> applyModel(String model, LLMProviderType type, String sessionId) {
        if (sessionId != null) SessionIds.check(sessionId);
        List<EngineSession> targets;
        if (sessionId == null) {
            targets = List.copyOf(sessions.values());
        } else {
            var session = find(sessionId);
            if (session == null) {
                throw sessionNotFound(sessionId);
            }
            targets = List.of(session);
        }
        return models.applyModel(targets, model, type, sessionId);
    }

    /** The per-session model sidecar entry, if the user picked a model for this session. */
    public SessionModelStore.Entry storedModel(String sessionId) {
        return models.stored(sessionId);
    }

    public void closeAll() {
        lifecycle.closeAll();
    }

    /**
     * Routes a memory extraction report to the session whose agent the run was forked from, as a
     * {@code memory} custom event. Reports for agents no live session owns are dropped.
     */
    private void publishMemoryActivity(Agent agent, MemoryExtractionReport report) {
        sessions.values().stream()
                .filter(session -> session.agent().equals(agent))
                .findFirst()
                .ifPresent(session -> dispatchMemoryActivity(session, report));
    }

    private void dispatchMemoryActivity(EngineSession session, MemoryExtractionReport report) {
        try {
            var data = MemoryActivityJson.payload(report).toString();
            session.inProcess().dispatchEvent(CustomEvent.of(session.id(), "memory", data, null, null));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to publish memory activity, sessionId={}, error={}", session.id(), e.getMessage());
        }
    }

    private EngineSession build(String sessionId, boolean load, SessionLock lock) {
        // Helpers are re-read per session, not from the bootstrap snapshot: on a live engine a
        // helper change in helpers.json must apply to the next session (design-agent-helpers §4.1).
        var helpers = HelperConfigStore.defaultStore().list();
        var agentConfig = new CliAgent.Config(bootstrap.result.llmProviders, null, bootstrap.maxTurn,
                bootstrap.sessionPersistence, bootstrap.workspace, NO_INTERACTIVE_USER,
                bootstrap.memoryEnabled, bootstrap.dailyLogsEnabled, bootstrap.coding, bootstrap.todoV2Enabled,
                sessionId, bootstrap.remoteAgents, bootstrap.remoteServers, bootstrap.subAgentConfigs,
                bootstrap.a2aAutoDiscover, bootstrap.mediaProvider,
                CliAppHelper.sessionMediaProvider(bootstrap.imageMediaProvider, sessionId),
                CliAppHelper.sessionMediaProvider(bootstrap.videoMediaProvider, sessionId),
                bootstrap.defaultImageModel, bootstrap.defaultVideoModel,
                bootstrap.props.property("media.image.edit.model").orElse(null),
                bootstrap.scheduledTaskStore, bootstrap.compressionConfig, clientType, helpers,
                bootstrap.mediaModelCatalog, null, bootstrap.disabledTools);
        var agent = CliAgent.of(agentConfig);
        if (load && agent.hasPersistenceProvider()) {
            switch (SessionLoads.load(bootstrap.sessionPersistence, sessionId)) {
                case SessionLoad.Ok ok -> agent.restore(sessionId, ok.domain());
                case SessionLoad.Missing ignored -> throw sessionNotFound(sessionId);
                case SessionLoad.Corrupt corrupt -> throw RpcException.business("SESSION_CORRUPT",
                        "session file is corrupted: " + sessionId, "reason", corrupt.reason());
                case SessionLoad.NewerVersion newer -> throw RpcException.business("SESSION_NEWER_VERSION",
                        "session was written by a newer core-ai version: " + sessionId,
                        "version", String.valueOf(newer.version()));
                case SessionLoad.IoError error -> throw new RpcException(RpcException.INTERNAL_ERROR,
                        "failed to read session: " + sessionId, null, error.cause());
            }
        }
        models.applyStored(sessionId, agent);
        var sink = sinkSupplier.get();
        var notification = sink != null ? sink : NOOP_SINK;
        var store = TranscriptStore.open(bootstrap.sessionsDir, sessionId);
        var metadata = new TranscriptMetadata(bootstrap.workspace.toString(), VersionUtil.getCurrentVersion(), clientType,
            agent::getModel, null);
        var recorder = new TranscriptRecorder(store, metadata, () -> agent.getMessages().size(),
            record -> SessionNotifications.notifyRecord(notification, sessionId, record));
        var seed = recorder.sessionStart(agent.getMessages());
        agent.seedTokenUsage(seed.toUsage(), seed.costUsd());
        var inProcess = new InProcessAgentSession(sessionId, agent, "full".equals(approvalPolicy), permissionStore(),
            false, recorder);
        inProcess.onEvent(new EventBridge(sessionId, notification));
        inProcess.onEvent(recorder);
        DesktopSessionTools.install(bootstrap.workspace, agent);
        return new EngineSession(sessionId, agent, inProcess, memoryProvider, lock, recorder);
    }

    private ToolPermissionStore permissionStore() {
        if (!"workspace-auto".equals(approvalPolicy)) {
            return bootstrap.permissionStore;
        }
        var store = workspaceAutoStore;
        if (store == null) {
            store = new WorkspaceAutoPermissionStore(bootstrap.permissionStore, bootstrap.workspace);
            workspaceAutoStore = store;
        }
        return store;
    }

    private EngineSession requireSession(ObjectNode params) {
        var id = SessionIds.check(Params.requiredText(params, "sessionId"));
        var session = sessions.get(id);
        if (session == null) {
            if (!bootstrap.sessionPersistence.exists(id)) throw sessionNotFound(id);
            session = resumeSession(id);
        }
        session.touch();
        return session;
    }

    String sessionTitle(String sessionId) {
        return titleResolver.sessionTitle(sessionId);
    }

    private boolean sessionFileExists(String id) {
        return bootstrap.sessionPersistence.exists(id);
    }

    public boolean sessionExists(String id) {
        return bootstrap.sessionPersistence.exists(id);
    }

}
