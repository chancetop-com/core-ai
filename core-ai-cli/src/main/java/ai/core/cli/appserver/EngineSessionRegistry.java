package ai.core.cli.appserver;

import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.CustomEvent;
import ai.core.agent.Agent;
import ai.core.agent.AttachedContent;
import ai.core.cli.CliAppHelper;
import ai.core.cli.agent.CliAgent;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemoryExtractionReport;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.utils.PathUtils;
import ai.core.llm.LLMProviderType;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final int MAX_TITLE_CHARS = 160;

    private static String truncate(String text, int max) {
        var oneLine = text.replaceAll("[\\r\\n]+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }

    private static RpcException sessionNotFound(String id) {
        return RpcException.business("SESSION_NOT_FOUND", "session not found: " + id, "sessionId", id);
    }

    private final EngineBootstrap bootstrap;
    private final Supplier<NotificationSink> sinkSupplier;
    private final SessionTitleStore titles;
    private final MdMemoryProvider memoryProvider;
    private final Map<String, EngineSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CachedTitle> titleCache = new ConcurrentHashMap<>();
    private volatile String approvalPolicy = "ask";
    private volatile String clientType = "cli";
    private volatile WorkspaceAutoPermissionStore workspaceAutoStore;

    public EngineSessionRegistry(EngineBootstrap bootstrap, Supplier<NotificationSink> sinkSupplier) {
        this.bootstrap = bootstrap;
        this.sinkSupplier = sinkSupplier;
        this.titles = new SessionTitleStore(Path.of(PathUtils.sessionsDir(bootstrap.workspace)));
        this.memoryProvider = bootstrap.memoryEnabled ? new MdMemoryProvider(bootstrap.workspace) : null;
        if (bootstrap.memoryEnabled) {
            MemoryTriggerService.getInstance().addActivityListener(this::publishMemoryActivity);
        }
    }

    public void setApprovalPolicy(String policy) {
        this.approvalPolicy = policy;
    }

    /** Trace origin declared by the connected client; sessions built afterwards upload under it. */
    public void setClientType(String clientType) {
        this.clientType = clientType;
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
            array.add(summary(info));
        }
        if (end < all.size()) {
            node.put("nextCursor", String.valueOf(end));
        }
        return node;
    }

    public ObjectNode create(ObjectNode params) {
        var id = CliAppHelper.defaultSessionId("cli-");
        var title = Params.optionalText(params, "title");
        if (title != null) {
            titles.set(id, title.strip());
        }
        sessions.put(id, build(id, false));
        var node = Params.object();
        node.put("sessionId", id);
        return node;
    }

    public ObjectNode resume(ObjectNode params) {
        var id = Params.requiredText(params, "sessionId");
        var session = sessions.get(id);
        if (session == null) {
            if (bootstrap.sessionPersistence.load(id).isEmpty()) {
                throw sessionNotFound(id);
            }
            session = build(id, true);
            sessions.put(id, session);
        }
        var node = Params.object();
        node.put("sessionId", id);
        node.put("status", session.running() ? "running" : "idle");
        node.put("messageCount", session.agent().getHistory().size());
        return node;
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
        var parsed = SendParts.parse(params, bootstrap.workspace, stagingDir(session.id()));
        if (parsed.text().isBlank()) {
            throw RpcException.invalidParams("message text is empty");
        }
        boolean queued = session.running();
        List<AttachedContent> attachments = parsed.attachments().isEmpty() ? null : parsed.attachments();
        session.inProcess().sendMessage(parsed.text(), null, attachments);
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
        var id = Params.requiredText(params, "sessionId");
        if (!sessions.containsKey(id) && bootstrap.sessionPersistence.load(id).isEmpty()) {
            throw sessionNotFound(id);
        }
        titles.set(id, Params.requiredText(params, "title").strip());
        titleCache.remove(id);
        var node = Params.object();
        node.put("sessionId", id);
        return node;
    }

    public ObjectNode delete(ObjectNode params) {
        var ids = Params.stringArray(params, "sessionIds");
        var toDelete = new ArrayList<String>();
        for (var id : ids) {
            var live = sessions.remove(id);
            if (live != null) {
                live.close();
            }
            titles.remove(id);
            titleCache.remove(id);
            if (sessionFileExists(id)) {
                toDelete.add(id);
            }
        }
        if (!toDelete.isEmpty()) {
            bootstrap.sessionPersistence.delete(toDelete);
        }
        return Params.object();
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
        var provider = bootstrap.result.llmProviders.getProvider(type);
        if (provider == null) {
            throw RpcException.business("MODEL_UNAVAILABLE", "provider is not configured: " + type.getName());
        }
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
        for (var session : targets) {
            var agent = session.agent();
            agent.setLlmProvider(provider);
            agent.setModel(model);
            var compression = agent.getCompression();
            if (compression != null) {
                compression.updateModel(agent.getLLMProvider(), model);
            }
        }
        return targets.stream().map(EngineSession::id).toList();
    }

    public void closeAll() {
        for (var session : sessions.values()) {
            try {
                session.save();
                session.close();
            } catch (RuntimeException e) {
                LOGGER.warn("failed to close session {}: {}", session.id(), e.getMessage());
            }
        }
        sessions.clear();
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

    private EngineSession build(String sessionId, boolean load) {
        var agentConfig = new CliAgent.Config(bootstrap.result.llmProviders, null, bootstrap.maxTurn,
                bootstrap.sessionPersistence, bootstrap.workspace, NO_INTERACTIVE_USER,
                bootstrap.memoryEnabled, bootstrap.dailyLogsEnabled, bootstrap.coding, bootstrap.todoV2Enabled,
                sessionId, bootstrap.remoteAgents, bootstrap.remoteServers, bootstrap.subAgentConfigs,
                bootstrap.a2aAutoDiscover, bootstrap.mediaProvider, bootstrap.imageMediaProvider,
                bootstrap.videoMediaProvider, bootstrap.defaultImageModel, bootstrap.defaultVideoModel,
                bootstrap.scheduledTaskStore, bootstrap.compressionConfig, clientType);
        var agent = CliAgent.of(agentConfig);
        if (load && agent.hasPersistenceProvider()) {
            agent.load(sessionId);
        }
        var inProcess = new InProcessAgentSession(sessionId, agent, "full".equals(approvalPolicy), permissionStore());
        var sink = sinkSupplier.get();
        inProcess.onEvent(new EventBridge(sessionId, sink != null ? sink : NOOP_SINK));
        return new EngineSession(sessionId, agent, inProcess, memoryProvider);
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
        var id = Params.requiredText(params, "sessionId");
        var session = sessions.get(id);
        if (session == null) {
            throw sessionNotFound(id);
        }
        return session;
    }

    private ObjectNode summary(SessionPersistence.SessionInfo info) {
        var node = Params.object();
        node.put("sessionId", info.id());
        node.put("title", title(info));
        node.put("updatedAt", info.lastModified().toString());
        var live = sessions.get(info.id());
        node.put("status", live != null && live.running() ? "running" : "idle");
        return node;
    }

    private String title(SessionPersistence.SessionInfo info) {
        var renamed = titles.title(info.id());
        if (renamed.isPresent()) {
            return renamed.get();
        }
        var cached = titleCache.get(info.id());
        if (cached != null && cached.updatedAt().equals(info.lastModified())) {
            return cached.title();
        }
        var first = bootstrap.sessionManager.firstUserMessage(info.id());
        var resolved = first == null || first.isBlank() ? info.id() : truncate(first.strip(), MAX_TITLE_CHARS);
        titleCache.put(info.id(), new CachedTitle(info.lastModified(), resolved));
        return resolved;
    }

    private boolean sessionFileExists(String id) {
        return Files.exists(Path.of(PathUtils.sessionsDir(bootstrap.workspace), id + ".data"));
    }

    private Path stagingDir(String sessionId) {
        return Path.of(System.getProperty("user.home"), ".core-ai", "app-server", "staging", sessionId);
    }

    private record CachedTitle(Instant updatedAt, String title) { }
}
