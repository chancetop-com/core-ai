package ai.core.cli.appserver;

import ai.core.cli.agent.AgentSessionRunnerHelper;
import ai.core.cli.upgrade.VersionUtil;
import ai.core.tool.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    // Trace origins for clients that declare themselves in the initialize handshake; extend this table when a
    // new client ships. Clients that send no name (or an unknown one) trace as "cli".
    private static final Map<String, String> TRACE_CLIENT_TYPES = Map.of("core-ai-desktop", "desktop");

    static String traceClientType(String clientName) {
        return clientName == null ? "cli" : TRACE_CLIENT_TYPES.getOrDefault(clientName, "cli");
    }

    private final Map<String, Function<ObjectNode, JsonNode>> handlers;
    private final EngineBootstrap bootstrap;
    private final EngineSessionRegistry registry;
    private final EngineScheduler scheduler;
    private final SchedulesApi schedulesApi;
    private final MediaSettingsApi mediaSettings;
    private final MediaGenerationApi mediaGeneration;
    private final UsageApi usageApi;
    private final ModelSettingsApi modelSettings;
    private final SpeechApi speechApi = new SpeechApi();
    private final HubBridge hubBridge = new HubBridge();
    private final HelperBridge helperBridge = new HelperBridge();
    private final Set<String> supportedMethods;
    private volatile NotificationSink sink = (method, params) -> { };

    public AppServerEngine(Path workspace, Path configFile) {
        this.bootstrap = new EngineBootstrap(workspace, configFile);
        this.registry = new EngineSessionRegistry(bootstrap, () -> sink);
        this.scheduler = new EngineScheduler(bootstrap, registry, () -> sink);
        this.registry.setScheduler(scheduler);
        this.schedulesApi = new SchedulesApi(bootstrap, registry, scheduler);
        this.mediaSettings = new MediaSettingsApi(bootstrap.props, bootstrap.workspace, bootstrap.imageMediaProvider,
                bootstrap.videoMediaProvider, bootstrap.mediaModelCatalog,
                Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties"));
        this.mediaGeneration = new MediaGenerationApi(bootstrap.workspace, bootstrap.imageMediaProvider,
                bootstrap.videoMediaProvider, bootstrap.defaultImageModel, bootstrap.defaultVideoModel, mediaSettings);
        this.usageApi = new UsageApi(bootstrap.sessionsDir, bootstrap.sessionPersistence);
        this.modelSettings = new ModelSettingsApi(bootstrap, registry);
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
        capabilities.add("transcript");
        capabilities.add("speech");
        capabilities.add("media_generation");
        capabilities.add("usage_summary");
        node.put("workspace", bootstrap.workspace.toString());
        node.set("trustState", trustNode());
        if (bootstrap.workspaceTrusted) scheduler.start();
        return node;
    }

    private ObjectNode trustNode() {
        var trust = Params.object();
        trust.put("state", bootstrap.workspaceTrusted ? "trusted" : "restricted");
        trust.put("key", bootstrap.workspaceTrust.key());
        trust.put("fingerprint", bootstrap.workspaceTrust.fingerprint());
        trust.put("hasExecutableInputs", bootstrap.workspaceTrust.hasExecutableInputs());
        trust.put("reason", bootstrap.workspaceTrust.reason());
        trust.put("engineRestricted", !bootstrap.workspaceTrusted);
        return trust;
    }

    private ObjectNode quiesce(ObjectNode params) {
        scheduler.pause();
        var node = registry.intake.quiesce(params, registry::activeSessions);
        if (!node.path("acquired").asBoolean(false)) scheduler.resume();
        return node;
    }

    private ObjectNode resumeIntake(ObjectNode params) {
        scheduler.resume();
        return registry.intake.resumeIntake(params);
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
        AppServerShutdown.close(scheduler, registry, bootstrap);
    }

    private Map<String, Function<ObjectNode, JsonNode>> buildHandlers() {
        var handlers = new HashMap<String, Function<ObjectNode, JsonNode>>(Map.ofEntries(
                Map.entry("session/list", registry::list), Map.entry("session/create", registry::create),
                Map.entry("session/resume", registry::resume), Map.entry("session/history", registry::history),
                Map.entry("session/records", registry::records), Map.entry("session/recordPayload", registry::recordPayload),
                Map.entry("session/send", registry::send), Map.entry("session/save", registry::save),
                Map.entry("session/close", registry::close), Map.entry("session/cancel", registry::cancel),
                Map.entry("session/approve", registry::approve), Map.entry("session/compact", registry::compact),
                Map.entry("session/undo", registry::undo),
                Map.entry("session/delete", registry::delete), Map.entry("session/rename", registry::rename),
                Map.entry("session/export", registry::export),
                Map.entry("session/stats", registry::stats),
                Map.entry("engine/quiesce", this::quiesce), Map.entry("engine/resumeIntake", this::resumeIntake),
                Map.entry("schedules/list", schedulesApi::list),
                Map.entry("schedules/get", schedulesApi::get),
                Map.entry("schedules/update", schedulesApi::update),
                Map.entry("schedules/delete", schedulesApi::delete),
                Map.entry("schedules/trigger", schedulesApi::trigger),
                Map.entry("model/list", ignored -> modelSettings.list()),
                Map.entry("model/get", modelSettings::get),
                Map.entry("model/set", modelSettings::set),
                Map.entry("thinking/get", modelSettings::thinkingGet),
                Map.entry("thinking/set", modelSettings::thinkingSet),
                Map.entry("maxturn/get", ignored -> maxTurnGet()),
                Map.entry("maxturn/set", this::maxTurnSet),
                Map.entry("media/get", ignored -> mediaSettings.get()), Map.entry("media/set", mediaSettings::set),
                Map.entry("speech/models", ignored -> speechApi.models()), Map.entry("speech/transcribe", speechApi::transcribe),
                Map.entry("permissions/get", ignored -> permissionsGet()),
                Map.entry("permissions/update", this::permissionsUpdate),
                Map.entry("workspace/trust", ignored -> trustNode()),
                Map.entry("artifacts/list", params -> LocalArtifacts.list(bootstrap.workspace, params)),
                Map.entry("memory/list", params -> LocalMemory.list(bootstrap.workspace, params)),
                Map.entry("memory/read", params -> LocalMemory.read(bootstrap.workspace, params)),
                Map.entry("memory/maintain", params -> LocalMemory.maintain(bootstrap.workspace, bootstrap.memoryEnabled,
                        params.path("deep").asBoolean(false))),
                Map.entry("skills/list", this::skillsList),
                Map.entry("skills/delete", params -> LocalSkills.delete(bootstrap.workspace, params)),
                Map.entry("tools/list", this::toolsList),
                Map.entry("hub/overview", hubBridge::overview),
                Map.entry("hub/catalog", params -> hubBridge.catalog(params.path("refresh").asBoolean(false))),
                Map.entry("hub/skills", hubBridge::skills),
                Map.entry("hub/skills/pull", hubBridge::pullSkill),
                Map.entry("hub/skills/update", hubBridge::updateSkill),
                Map.entry("helpers/list", ignored -> helperBridge.list()),
                Map.entry("helpers/add", helperBridge::add),
                Map.entry("helpers/update", helperBridge::update),
                Map.entry("helpers/remove", helperBridge::remove)));
        handlers.putAll(extraHandlers());
        return Map.copyOf(handlers);
    }

    private Map<String, Function<ObjectNode, JsonNode>> extraHandlers() {
        return Map.of(
                "media/models", ignored -> mediaGeneration.models(),
                "media/generate", params -> {
                    bootstrap.requireTrusted("generating media");
                    return mediaGeneration.generate(params);
                },
                "media/jobs/get", params -> {
                    bootstrap.requireTrusted("reading media jobs");
                    return mediaGeneration.job(params);
                },
                "media/jobs/list", params -> {
                    bootstrap.requireTrusted("reading media jobs");
                    return mediaGeneration.jobs(params);
                },
                "memory/write", params -> {
                    bootstrap.requireTrusted("writing memory");
                    return LocalMemory.write(bootstrap.workspace, params);
                },
                "usage/summary", usageApi::summary);
    }

    private ObjectNode skillsList(ObjectNode params) {
        if (params.path("localOnly").asBoolean(false)) {
            return LocalSkills.list(bootstrap.workspace, null, hubBridge.signedIn());
        }
        var digests = hubBridge.skillDigests();
        return LocalSkills.list(bootstrap.workspace, digests.digests(), digests.signedIn());
    }


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
