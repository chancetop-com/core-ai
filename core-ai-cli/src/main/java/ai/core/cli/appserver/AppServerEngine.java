package ai.core.cli.appserver;

import ai.core.cli.CliAppHelper;
import ai.core.cli.agent.AgentSessionRunnerHelper;
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
 * thinking control, permissions, local artifacts / skills). Turns run on each session's own driver
 * thread; this class stays on the dispatch thread.
 *
 * @author stephen
 */
public class AppServerEngine implements EngineApi {
    public static final String PROTOCOL_VERSION = "1.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(AppServerEngine.class);

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

    private final Map<String, Function<ObjectNode, JsonNode>> handlers;
    private final EngineBootstrap bootstrap;
    private final EngineSessionRegistry registry;
    private final Set<String> supportedMethods;
    private volatile NotificationSink sink = (method, params) -> { };

    public AppServerEngine(Path workspace, Path configFile) {
        this.bootstrap = new EngineBootstrap(workspace, configFile);
        this.registry = new EngineSessionRegistry(bootstrap, () -> sink);
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
        var node = Params.object();
        node.put("engineVersion", VersionUtil.getCurrentVersion());
        node.put("protocolVersion", PROTOCOL_VERSION);
        var capabilities = node.putArray("capabilities");
        capabilities.add("plan_update");
        capabilities.add("compression_event");
        capabilities.add("session_export");
        capabilities.add("custom_events");
        node.put("workspace", bootstrap.workspace.toString());
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
                Map.entry("model/list", ignored -> modelList()),
                Map.entry("model/get", this::modelGet),
                Map.entry("model/set", this::modelSet),
                Map.entry("thinking/get", ignored -> thinkingGet()),
                Map.entry("thinking/set", this::thinkingSet),
                Map.entry("permissions/get", ignored -> permissionsGet()),
                Map.entry("permissions/update", this::permissionsUpdate),
                Map.entry("artifacts/list", params -> LocalArtifacts.list(bootstrap.workspace, params)),
                Map.entry("memory/list", params -> LocalMemory.list(bootstrap.workspace, params)),
                Map.entry("memory/read", params -> LocalMemory.read(bootstrap.workspace, params)),
                Map.entry("skills/list", ignored -> LocalSkills.list(bootstrap.workspace)),
                Map.entry("tools/list", this::toolsList));
    }

    private ObjectNode modelList() {
        var node = Params.object();
        var array = node.putArray("models");
        for (var entry : bootstrap.modelRegistry.getAllEntries()) {
            var item = array.addObject();
            item.put("model", entry.model());
            item.put("provider", entry.providerType().getName());
        }
        return node;
    }

    /**
     * Current model: the live session's agent when one exists (optionally a specific session),
     * otherwise what a fresh engine would resolve from agent.properties.
     */
    private ObjectNode modelGet(ObjectNode params) {
        var node = Params.object();
        var sessionId = Params.optionalText(params, "sessionId");
        var session = sessionId != null ? registry.find(sessionId) : registry.any();
        if (session != null) {
            var agent = session.agent();
            node.put("model", agent.getModel() != null ? agent.getModel() : agent.getLLMProvider().config.getModel());
            var type = bootstrap.result.llmProviders.getProviderType(agent.getLLMProvider());
            if (type != null) {
                node.put("provider", type.getName());
            }
            return node;
        }
        try {
            var file = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");
            var props = loadAgentProperties(file);
            var active = props.getProperty("active.provider");
            if (active != null && !active.isBlank()) {
                node.put("provider", active);
                var model = props.getProperty(active + ".model");
                var type = LLMProviderType.fromName(active);
                if ((model == null || model.isBlank()) && type != null) {
                    model = LLMProviders.getProviderDefaultChatModel(type);
                }
                if (model != null && !model.isBlank()) {
                    node.put("model", model.trim());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("failed to read active model: {}", e.getMessage());
        }
        return node;
    }

    private ObjectNode modelSet(ObjectNode params) {
        var model = Params.requiredText(params, "model");
        var type = bootstrap.modelRegistry.getProviderType(model);
        if (type == null) {
            throw RpcException.business("MODEL_UNKNOWN", "model is not in the registry: " + model);
        }
        var applied = registry.applyModel(model, type, Params.optionalText(params, "sessionId"));
        persistActiveModel(type, model);
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
