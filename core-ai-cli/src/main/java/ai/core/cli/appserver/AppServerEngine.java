package ai.core.cli.appserver;

import ai.core.api.server.gateway.GatewayAvailableModelView;
import ai.core.cli.CliAppHelper;
import ai.core.cli.agent.AgentSessionRunnerHelper;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hub.model.ModelHubClient;
import ai.core.cli.upgrade.VersionUtil;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.tool.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Real app-server engine: one-time config bootstrap, then the v1 method surface (sessions, model /
 * thinking control, permissions, local artifacts / skills) plus the hub bridge (server capability
 * catalog and skill pull). Turns run on each session's own driver thread; this class stays on the
 * dispatch thread.
 *
 * @author stephen
 */
public class AppServerEngine implements EngineApi {
    public static final String PROTOCOL_VERSION = "1.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(AppServerEngine.class);
    private static final long SERVER_MODELS_TTL_MS = 60_000L;
    // Trace origins for clients that declare themselves in the initialize handshake; extend this table when a
    // new client ships. Clients that send no name (or an unknown one) trace as "cli".
    private static final Map<String, String> TRACE_CLIENT_TYPES = Map.of("core-ai-desktop", "desktop");

    static String traceClientType(String clientName) {
        return clientName == null ? "cli" : TRACE_CLIENT_TYPES.getOrDefault(clientName, "cli");
    }

    private static Properties loadAgentProperties(Path file) throws IOException {
        var props = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        return props;
    }

    private static void storeAgentProperties(Path file, Properties props) throws IOException {
        var parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (var out = Files.newOutputStream(file)) {
            props.store(out, null);
        }
    }

    private static String defaultChatModelFor(String providerName) {
        try {
            return LLMProviders.getProviderDefaultChatModel(LLMProviderType.fromName(providerName));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean loggedIn() {
        var auth = AuthConfig.load();
        return auth != null && auth.apiKey() != null;
    }

    private final Map<String, Function<ObjectNode, JsonNode>> handlers;
    private final EngineBootstrap bootstrap;
    private final EngineSessionRegistry registry;
    private final EngineScheduler scheduler;
    private final SchedulesApi schedulesApi;
    private final HubBridge hubBridge = new HubBridge();
    private final HelperBridge helperBridge = new HelperBridge();
    private final Set<String> supportedMethods;
    private volatile NotificationSink sink = (method, params) -> { };
    private volatile List<GatewayAvailableModelView> cachedServerModels;
    private volatile long serverModelsFetchedAt;

    public AppServerEngine(Path workspace, Path configFile) {
        this.bootstrap = new EngineBootstrap(workspace, configFile);
        this.registry = new EngineSessionRegistry(bootstrap, () -> sink);
        this.scheduler = new EngineScheduler(bootstrap, registry, () -> sink);
        this.registry.setScheduler(scheduler);
        this.schedulesApi = new SchedulesApi(bootstrap, registry, scheduler);
        this.handlers = buildHandlers();
        this.supportedMethods = Set.copyOf(handlers.keySet());
    }

    @Override
    public void attach(NotificationSink sink) {
        this.sink = sink;
    }

    @Override
    public Set<String> supportedMethods() {
        return supportedMethods;
    }

    @Override
    public JsonNode initialize(ObjectNode params) {
        var requested = params.path("protocolVersion").asText("");
        if (!requested.startsWith("1.")) {
            var data = Params.object();
            data.put("code", "PROTOCOL_VERSION_MISMATCH");
            data.putArray("supportedVersions").add(PROTOCOL_VERSION);
            throw new RpcException(RpcException.INVALID_REQUEST, "unsupported protocolVersion: " + requested, data);
        }
        var policy = params.path("approvalPolicy").asText("ask");
        if (!List.of("ask", "workspace-auto", "full").contains(policy)) {
            throw RpcException.invalidParams("unknown approvalPolicy: " + policy);
        }
        registry.setApprovalPolicy(policy);
        registry.setClientType(traceClientType(params.path("client").path("name").asText(null)));
        var node = Params.object();
        node.put("engineVersion", VersionUtil.getCurrentVersion());
        node.put("protocolVersion", PROTOCOL_VERSION);
        var capabilities = node.putArray("capabilities");
        capabilities.add("plan_update");
        capabilities.add("compression_event");
        capabilities.add("session_export");
        capabilities.add("custom_events");
        capabilities.add("scheduled_tasks");
        node.put("workspace", bootstrap.workspace.toString());
        scheduler.start();
        return node;
    }

    @Override
    public JsonNode call(String method, ObjectNode params) {
        var handler = handlers.get(method);
        if (handler == null) {
            throw RpcException.methodNotFound(method);
        }
        return handler.apply(params);
    }

    @Override
    public void shutdown() {
        scheduler.close();
        registry.closeAll();
        CliAppHelper.closeShutdownResources(bootstrap.result);
    }

    private Map<String, Function<ObjectNode, JsonNode>> buildHandlers() {
        return Map.ofEntries(
                Map.entry("session/list", registry::list),
                Map.entry("session/create", registry::create),
                Map.entry("session/resume", registry::resume),
                Map.entry("session/history", registry::history),
                Map.entry("session/send", registry::send),
                Map.entry("session/cancel", registry::cancel),
                Map.entry("session/approve", registry::approve),
                Map.entry("session/compact", registry::compact),
                Map.entry("session/undo", registry::undo),
                Map.entry("session/delete", registry::delete),
                Map.entry("session/rename", registry::rename),
                Map.entry("session/export", registry::export),
                Map.entry("session/stats", registry::stats),
                Map.entry("schedules/list", schedulesApi::list),
                Map.entry("schedules/get", schedulesApi::get),
                Map.entry("schedules/update", schedulesApi::update),
                Map.entry("schedules/delete", schedulesApi::delete),
                Map.entry("schedules/trigger", schedulesApi::trigger),
                Map.entry("model/list", ignored -> modelList()),
                Map.entry("model/get", this::modelGet),
                Map.entry("model/set", this::modelSet),
                Map.entry("thinking/get", ignored -> thinkingGet()),
                Map.entry("thinking/set", this::thinkingSet),
                Map.entry("maxturn/get", ignored -> maxTurnGet()),
                Map.entry("maxturn/set", this::maxTurnSet),
                Map.entry("permissions/get", ignored -> permissionsGet()),
                Map.entry("permissions/update", this::permissionsUpdate),
                Map.entry("artifacts/list", params -> LocalArtifacts.list(bootstrap.workspace, params)),
                Map.entry("memory/list", params -> LocalMemory.list(bootstrap.workspace, params)),
                Map.entry("memory/read", params -> LocalMemory.read(bootstrap.workspace, params)),
                Map.entry("skills/list", ignored -> LocalSkills.list(bootstrap.workspace, hubBridge.skillClientOrNull())),
                Map.entry("tools/list", this::toolsList),
                Map.entry("hub/catalog", ignored -> hubBridge.catalog()),
                Map.entry("hub/skills", hubBridge::skills),
                Map.entry("hub/skills/pull", hubBridge::pullSkill),
                Map.entry("hub/skills/update", hubBridge::updateSkill),
                Map.entry("helpers/list", ignored -> helperBridge.list()),
                Map.entry("helpers/add", helperBridge::add),
                Map.entry("helpers/update", helperBridge::update),
                Map.entry("helpers/remove", helperBridge::remove));
    }

    private ObjectNode modelList() {
        var serverModels = serverModels();
        if (serverModels != null) {
            var node = Params.object();
            node.put("source", "server");
            var array = node.putArray("models");
            for (var model : serverModels) {
                var item = array.addObject();
                item.put("model", model.modelId);
                if (model.displayName != null) item.put("displayName", model.displayName);
                if (model.providerName != null) item.put("provider", model.providerName);
                if (model.reasoningEfforts != null && !model.reasoningEfforts.isEmpty()) {
                    var efforts = item.putArray("reasoningEfforts");
                    model.reasoningEfforts.forEach(efforts::add);
                }
            }
            return node;
        }
        var node = Params.object();
        node.put("source", "local");
        var array = node.putArray("models");
        for (var entry : bootstrap.modelRegistry.getAllEntries()) {
            var item = array.addObject();
            item.put("model", entry.model());
            item.put("provider", entry.providerType().getName());
        }
        return node;
    }

    /**
     * Chat models the logged-in user may use, from the hub ({@code /api/hub/models}). Null when not
     * logged in or unreachable — the caller then falls back to the local registry. Cached briefly so
     * an offline server cannot stall every model/list call.
     */
    private List<GatewayAvailableModelView> serverModels() {
        var now = System.currentTimeMillis();
        if (serverModelsFetchedAt != 0 && now - serverModelsFetchedAt < SERVER_MODELS_TTL_MS) {
            return cachedServerModels;
        }
        List<GatewayAvailableModelView> fetched = null;
        try {
            var auth = AuthConfig.load();
            if (auth != null && auth.apiKey() != null && auth.serverUrl() != null) {
                fetched = new ModelHubClient(auth.serverUrl(), auth.apiKey(), auth.insecure()).models().models;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to fetch hub models: {}", e.getMessage());
        }
        cachedServerModels = fetched;
        serverModelsFetchedAt = now;
        return fetched;
    }

    /**
     * Current model: the given session's model when a sessionId is passed (its live agent, else the
     * per-session pick it would rebuild with, else the default), otherwise what a fresh engine would
     * resolve from agent.properties.
     */
    private ObjectNode modelGet(ObjectNode params) {
        var node = Params.object();
        var sessionId = Params.optionalText(params, "sessionId");
        if (sessionId != null) {
            var scoped = sessionModel(sessionId);
            if (scoped != null) {
                return scoped;
            }
        }
        try {
            var file = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");
            var props = loadAgentProperties(file);
            var active = props.getProperty("active.provider");
            var prefix = active != null && !active.isBlank() ? active : "llm";
            node.put("provider", prefix);
            var model = props.getProperty(prefix + ".model");
            if (model == null || model.isBlank()) {
                model = defaultChatModelFor(prefix);
            }
            if (model != null && !model.isBlank()) {
                node.put("model", model.trim());
            }
        } catch (IOException e) {
            LOGGER.warn("failed to read active model: {}", e.getMessage());
        }
        return node;
    }

    /** A session's model: its live agent, else the stored per-session pick, else null. */
    private ObjectNode sessionModel(String sessionId) {
        var session = registry.find(sessionId);
        if (session != null) {
            var node = Params.object();
            var agent = session.agent();
            node.put("model", agent.getModel() != null ? agent.getModel() : agent.getLLMProvider().config.getModel());
            var type = bootstrap.result.llmProviders.getProviderType(agent.getLLMProvider());
            if (type != null) {
                node.put("provider", type.getName());
            }
            return node;
        }
        var stored = registry.storedModel(sessionId);
        if (stored == null) {
            return null;
        }
        var node = Params.object();
        node.put("model", stored.model);
        if (stored.provider != null) {
            node.put("provider", stored.provider);
        }
        return node;
    }

    private ObjectNode modelSet(ObjectNode params) {
        var model = Params.requiredText(params, "model");
        var type = bootstrap.modelRegistry.getProviderType(model);
        if (type == null && loggedIn()) {
            // a server-served model: local runs go through the litellm proxy, the gateway routes it
            type = LLMProviderType.LITELLM;
        }
        if (type == null) {
            throw RpcException.business("MODEL_UNKNOWN", "model is not in the registry: " + model);
        }
        var sessionId = Params.optionalText(params, "sessionId");
        var applied = registry.applyModel(model, type, sessionId);
        if (sessionId == null) {
            // no session scope: the pick also becomes the default future sessions resolve
            persistActiveModel(type, model);
        }
        var node = Params.object();
        node.put("model", model);
        node.put("provider", type.getName());
        var array = node.putArray("appliedTo");
        applied.forEach(array::add);
        return node;
    }

    private void persistActiveModel(LLMProviderType type, String model) {
        try {
            var file = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");
            var props = loadAgentProperties(file);
            props.setProperty(type.getName() + ".model", model);
            props.setProperty("active.provider", type.getName());
            storeAgentProperties(file, props);
            bootstrap.result.llmProviders.setDefaultProvider(type);
        } catch (IOException e) {
            LOGGER.warn("failed to persist active model: {}", e.getMessage());
        }
    }

    private ObjectNode thinkingGet() {
        var effort = AgentSessionRunnerHelper.loadReasoningEffortFromExtraBody();
        var node = Params.object();
        node.put("level", effort == null ? "off" : effort.name().toLowerCase(Locale.ROOT));
        return node;
    }

    private ObjectNode thinkingSet(ObjectNode params) {
        var level = Params.requiredText(params, "level").toLowerCase(Locale.ROOT);
        // "off" clears the reasoning_effort key (provider default); everything else maps to none/low/high/max
        ReasoningEffort effort = "off".equals(level) ? null : AgentSessionRunnerHelper.parseLevel(level);
        if (effort == null && !"off".equals(level)) {
            throw RpcException.invalidParams("invalid level: " + level + " (use none, low, high, max or off)");
        }
        var error = AgentSessionRunnerHelper.persistReasoningEffortToExtraBody(effort);
        if (error != null) {
            throw RpcException.business("CONFIG_WRITE_FAILED", error);
        }
        var node = Params.object();
        node.put("level", effort == null ? "off" : effort.name().toLowerCase(Locale.ROOT));
        node.put("restartRequired", true);
        return node;
    }

    /** The effective limit comes from the engine's bootstrap (agent.max.turn, default 100). */
    private ObjectNode maxTurnGet() {
        var node = Params.object();
        node.put("value", bootstrap.maxTurn);
        return node;
    }

    private ObjectNode maxTurnSet(ObjectNode params) {
        int value = Params.intParam(params, "value", 0);
        if (value < 1) {
            throw RpcException.invalidParams("value must be a positive integer");
        }
        var error = AgentSessionRunnerHelper.persistMaxTurn(value);
        if (error != null) {
            throw RpcException.business("CONFIG_WRITE_FAILED", error);
        }
        var node = Params.object();
        node.put("value", value);
        node.put("restartRequired", true);
        return node;
    }

    private ObjectNode permissionsGet() {
        var node = Params.object();
        var allow = node.putArray("allow");
        bootstrap.permissionStore.getAllowPatterns().forEach(allow::add);
        var deny = node.putArray("deny");
        bootstrap.permissionStore.getDenyPatterns().forEach(deny::add);
        return node;
    }

    /**
     * v1 semantics: the provided patterns are merged into the live rule store (additive); removal
     * needs a store API extension and is a follow-up. Default whitelist entries are re-added on
     * every engine start, so the desktop should mark them as engine managed.
     */
    private ObjectNode permissionsUpdate(ObjectNode params) {
        for (var pattern : Params.stringArray(params, "allow")) {
            bootstrap.permissionStore.allow(pattern);
        }
        for (var pattern : Params.stringArray(params, "deny")) {
            bootstrap.permissionStore.deny(pattern);
        }
        return permissionsGet();
    }

    private ObjectNode toolsList(ObjectNode params) {
        var sessionId = Params.optionalText(params, "sessionId");
        var session = sessionId != null ? registry.find(sessionId) : registry.any();
        var tools = session == null ? List.<ToolCall>of() : session.agent().getToolCalls();
        var node = Params.object();
        var array = node.putArray("tools");
        for (var tool : tools) {
            var item = array.addObject();
            item.put("name", tool.getName());
            var description = tool.getDescription();
            item.put("description", description == null ? "" : description);
        }
        return node;
    }
}
