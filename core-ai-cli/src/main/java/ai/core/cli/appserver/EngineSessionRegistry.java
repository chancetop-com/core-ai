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
import ai.core.cli.utils.PathUtils;
import ai.core.llm.LLMProviderType;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.RoleType;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GetVideoStatusTool;
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
    private final EngineSessionModels models;
    private final MdMemoryProvider memoryProvider;
    private final Map<String, EngineSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, CachedTitle> titleCache = new ConcurrentHashMap<>();
    private volatile String approvalPolicy = "ask";
    private volatile String clientType = "cli";
    private volatile WorkspaceAutoPermissionStore workspaceAutoStore;
    private volatile EngineScheduler scheduler;

    public EngineSessionRegistry(EngineBootstrap bootstrap, Supplier<NotificationSink> sinkSupplier) {
        this.bootstrap = bootstrap;
        this.sinkSupplier = sinkSupplier;
        this.titles = new SessionTitleStore(Path.of(PathUtils.sessionsDir(bootstrap.workspace)));
        this.models = new EngineSessionModels(bootstrap.result.llmProviders, Path.of(PathUtils.sessionsDir(bootstrap.workspace)));
        this.memoryProvider = bootstrap.memoryEnabled ? new MdMemoryProvider(bootstrap.workspace) : null;
        if (bootstrap.memoryEnabled) {
            MemoryTriggerService.getInstance(bootstrap.workspace).addActivityListener(this::publishMemoryActivity);
        }
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
        var session = sessions.computeIfAbsent(id, sessionId -> {
            if (bootstrap.sessionPersistence.load(sessionId).isEmpty()) {
                throw sessionNotFound(sessionId);
            }
            return build(sessionId, true);
        });
        var node = Params.object();
        node.put("sessionId", id);
        node.put("status", session.running() ? "running" : "idle");
        node.put("messageCount", session.agent().getHistory().size());
        return node;
    }

    /** Used by the scheduler: rebuild a persisted session quietly, never create a new one. */
    EngineSession resumeQuietly(String sessionId) {
        return sessionFileExists(sessionId) ? sessions.computeIfAbsent(sessionId, id -> build(id, true)) : null;
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
        if (!parsed.hasContent()) {
            throw RpcException.invalidParams("message must contain text or attachments");
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
            models.remove(id);
            titleCache.remove(id);
            var removedTasks = bootstrap.scheduledTaskStore.list(id);
            if (!removedTasks.isEmpty()) {
                bootstrap.scheduledTaskStore.deleteBySession(id);
                removedTasks.forEach(task -> EngineScheduler.sendChangedNotification(sinkSupplier.get(), task.id, "session_deleted"));
            }
            if (sessionFileExists(id)) {
                toDelete.add(id);
            }
        }
        if (!toDelete.isEmpty()) {
            bootstrap.sessionPersistence.delete(toDelete);
        }
        if (scheduler != null) scheduler.refreshSnapshot();
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
                bootstrap.mediaModelCatalog);
        var agent = CliAgent.of(agentConfig);
        if (load && agent.hasPersistenceProvider()) {
            agent.load(sessionId);
        }
        models.applyStored(sessionId, agent);
        var inProcess = new InProcessAgentSession(sessionId, agent, "full".equals(approvalPolicy), permissionStore());
        var sink = sinkSupplier.get();
        inProcess.onEvent(new EventBridge(sessionId, sink != null ? sink : NOOP_SINK));
        var context = agent.getExecutionContext();
        var artifactSink = new LocalArtifactSink(bootstrap.workspace, context);
        context.getCustomVariables().put(GenerateImageTool.IMAGE_OUTPUT_SINK_CONTEXT_KEY, artifactSink);
        context.getCustomVariables().put(GetVideoStatusTool.VIDEO_OUTPUT_SINK_CONTEXT_KEY, artifactSink);
        installArtifactDelivery(agent);
        return new EngineSession(sessionId, agent, inProcess, memoryProvider);
    }

    /**
     * Desktop deliverable flow: non-media files enter the artifact panel only when the agent registers
     * them through submit_artifacts (media files are captured by the sink automatically). The
     * instructions go into the prompt template and, for a resumed session, into the already-sent
     * messages[0] — the copy the model actually reads (same reason as MemorySectionManager).
     */
    private void installArtifactDelivery(Agent agent) {
        agent.addTools(List.of(LocalSubmitArtifactsTool.create(bootstrap.workspace)));
        var prompt = agent.getSystemPrompt();
        if (prompt == null || prompt.contains(LocalSubmitArtifactsTool.PROMPT_MARKER)) {
            return;
        }
        agent.setSystemPrompt(LocalSubmitArtifactsTool.appendInstructions(prompt));
        var messages = agent.getMessages();
        if (messages.isEmpty()) {
            return;
        }
        var first = messages.getFirst();
        if (first.role != RoleType.SYSTEM || first.content == null || first.content.isEmpty()) {
            return;
        }
        var text = first.content.getFirst().text;
        if (text == null || text.contains(LocalSubmitArtifactsTool.PROMPT_MARKER)) {
            return;
        }
        first.content = List.of(Content.of(LocalSubmitArtifactsTool.appendInstructions(text)));
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

    /** Title for the schedules page: renamed sidecar first, then the first user message. */
    String sessionTitle(String sessionId) {
        var renamed = titles.title(sessionId);
        if (renamed.isPresent()) return renamed.get();
        var first = bootstrap.sessionManager.firstUserMessage(sessionId);
        return first == null || first.isBlank() ? null : truncate(first.strip(), MAX_TITLE_CHARS);
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
