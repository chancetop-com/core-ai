package ai.core.cli.acp;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.CliApp;
import ai.core.cli.agent.CliAgent;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.RuntimeAuthConfig;
import ai.core.cli.hook.ScriptHookLifecycle;
import ai.core.cli.log.CliLogger;
import ai.core.cli.memory.SessionCloseExtractor;
import ai.core.cli.remote.A2ARemoteAgentConfig;
import ai.core.cli.remote.A2ARemoteAgentConfigLoader;
import ai.core.cli.remote.A2ARemoteServerConfig;
import ai.core.cli.utils.PathUtils;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.session.FileRuleBasedPermissionStore;
import ai.core.session.FileSessionPersistence;
import ai.core.session.PermissionGate;
import ai.core.session.ServerPermissionLifecycle;
import ai.core.session.ToolPermissionStore;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.MemoryTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.TaskTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteTodosTool;
import ai.core.utils.JsonUtil;
import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.agentclientprotocol.sdk.spec.AcpSchema.AvailableCommand;
import static com.agentclientprotocol.sdk.spec.AcpSchema.AvailableCommandInput;
import static com.agentclientprotocol.sdk.spec.AcpSchema.AvailableCommandsUpdate;

/**
 * ACP (Agent Client Protocol) agent runner — bridges ACP stdio transport
 * to the core-ai Agent.
 */
public class AcpAgentRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AcpAgentRunner.class);

    private final Path configFile;
    private final String modelOverride;
    private final boolean autoApproveAll;
    private final Path workspace;
    private final AcpSlashCommandHandler slashCommands;
    private final Map<String, AcpSession> sessions = new ConcurrentHashMap<>();
    private final AtomicReference<AcpSyncAgent> acpAgentRef = new AtomicReference<>();

    public AcpAgentRunner(Path configFile, String modelOverride, boolean autoApproveAll, Path workspace) {
        this.configFile = configFile != null ? configFile : PathUtils.DEFAULT_CONFIG;
        this.modelOverride = modelOverride;
        this.autoApproveAll = autoApproveAll;
        this.workspace = workspace != null ? workspace.toAbsolutePath() : Paths.get("").toAbsolutePath();
        this.slashCommands = new AcpSlashCommandHandler(this.workspace);
    }

    /**
     * Returns the list of available slash commands for ACP client autocomplete.
     */
    public List<AcpSlashCommandHandler.CommandInfo> getSlashCommands() {
        return slashCommands.listCommands();
    }

    /**
     * Runs the ACP agent: initializes core-ai infrastructure, sets up stdio transport,
     * and blocks until the transport closes.
     */
    public void run() {
        System.setProperty("core.appName", "core-ai-cli");
        System.setProperty("user.dir", workspace.toString());

        var props = PropertiesFileSource.fromFile(configFile);
        CliApp.mergeWorkspaceConfig(props, workspace);
        mergeWorkspaceMcpConfig(props);
        injectLiteLLMFallback(props);
        var bootstrap = new AgentBootstrap(props);
        registerMcpLoadingListener();
        var result = bootstrap.initialize();
        LOGGER.info("ACP agent bootstrap initialized");

        registerAuthListener(result);

        var ctx = new AgentContext(result,
                props.property("agent.memory.enabled").map(Boolean::parseBoolean).orElse(false),
                props.property("agent.memory.daily.logs.enabled").map(Boolean::parseBoolean).orElse(false),
                props.property("agent.coding.enabled").map(Boolean::parseBoolean).orElse(false),
                props.property("agent.max.turn").map(Integer::parseInt).orElse(100),
                A2ARemoteAgentConfigLoader.load(props),
                A2ARemoteAgentConfigLoader.loadServers(props));
        restoreActiveProvider(props, result.llmProviders);

        var agent = buildAgent(ctx);
        acpAgentRef.set(agent);
        LOGGER.info("ACP agent starting...");
        agent.run();
        LOGGER.info("ACP agent terminated");

        sessions.forEach((sid, acpSession) -> {
            persistSession(acpSession);
            if (ctx.memoryEnabled) {
                SessionCloseExtractor.onSessionClose(acpSession.agent(), workspace, ctx.memoryEnabled, ctx.dailyLogsEnabled, null);
            }
        });
        ScriptHookLifecycle.fireSessionStopHooks(workspace);
    }

    @SuppressWarnings("unchecked")
    private AcpSyncAgent buildAgent(AgentContext ctx) {
        var transport = new StdioAcpAgentTransport();
        return AcpAgent.sync(transport)
                .initializeHandler(req -> {
                    LOGGER.info("ACP client initialized: protocolVersion={}", req.protocolVersion());
                    var caps = new AcpSchema.AgentCapabilities(
                            true,
                            new AcpSchema.McpCapabilities(),
                            new AcpSchema.PromptCapabilities());
                    return AcpSchema.InitializeResponse.ok(caps);
                })
                .newSessionHandler(req -> {
                    var response = handleNewSession(req, ctx);
                    broadcastAvailableCommands(response.sessionId());
                    return response;
                })
                .loadSessionHandler(req -> {
                    var response = handleLoadSession(req, ctx);
                    broadcastAvailableCommands(req.sessionId());
                    return response;
                })
                .promptHandler((req, promptCtx) -> handlePrompt(req, promptCtx, ctx))
                .cancelHandler(notification -> {
                    var acpSession = sessions.get(notification.sessionId());
                    if (acpSession != null) {
                        LOGGER.info("ACP cancel: session={}", notification.sessionId());
                        acpSession.agent().cancel();
                    }
                })
                .build();
    }

    private AcpSchema.NewSessionResponse handleNewSession(AcpSchema.NewSessionRequest req, AgentContext ctx) {
        Path effectiveWorkspace = req.cwd() != null && !req.cwd().isBlank()
                ? Path.of(req.cwd())
                : workspace;
        String sessionId = "acp-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        LOGGER.info("ACP newSession: id={}, cwd={}", sessionId, effectiveWorkspace);

        CliLogger.initialize(workspace, sessionId);

        var sessionPersistence = new FileSessionPersistence(PathUtils.sessionsDir(workspace));
        var agentConfig = new CliAgent.Config(
                ctx.result.llmProviders, modelOverride, ctx.maxTurn, sessionPersistence,
                effectiveWorkspace,
                question -> "(user input not available in ACP mode)",
                ctx.memoryEnabled, ctx.dailyLogsEnabled, ctx.coding, false, sessionId, ctx.remoteAgents, ctx.remoteServers,
                   Map.of(), false);

        Agent coreAgent = CliAgent.of(agentConfig);
        if (coreAgent.hasPersistenceProvider()) {
            coreAgent.load(sessionId);
        }
        var currentContext = new AtomicReference<SyncPromptContext>();
        var permissionGate = new PermissionGate();
        coreAgent.addLifecycle(new ServerPermissionLifecycle(
                sessionId,
                acpPermissionDispatcher(currentContext, permissionGate),
                permissionGate,
                autoApproveAll,
                whiteToolsPermissionStore(),
                toolName -> "builtin"));
        sessions.put(sessionId, new AcpSession(coreAgent, sessionId, currentContext, sessionPersistence));
        return new AcpSchema.NewSessionResponse(sessionId, null, null);
    }

    private AcpSchema.LoadSessionResponse handleLoadSession(AcpSchema.LoadSessionRequest req, AgentContext ctx) {
        var sessionId = req.sessionId();
        LOGGER.info("ACP loadSession: id={}", sessionId);

        var existing = sessions.get(sessionId);
        if (existing != null) {
            return new AcpSchema.LoadSessionResponse(null, null);
        }

        Path effectiveWorkspace = req.cwd() != null && !req.cwd().isBlank()
                ? Path.of(req.cwd())
                : workspace;

        CliLogger.initialize(workspace, sessionId);

        var sessionPersistence = new FileSessionPersistence(PathUtils.sessionsDir(workspace));
        var agentConfig = new CliAgent.Config(
                ctx.result.llmProviders, modelOverride, ctx.maxTurn, sessionPersistence,
                effectiveWorkspace,
                question -> "(user input not available in ACP mode)",
                ctx.memoryEnabled, ctx.dailyLogsEnabled, ctx.coding, false, sessionId, ctx.remoteAgents, ctx.remoteServers,
                Map.of(), false);

        Agent coreAgent = CliAgent.of(agentConfig);
        if (coreAgent.hasPersistenceProvider()) {
            coreAgent.load(sessionId);
        }
        var currentContext = new AtomicReference<SyncPromptContext>();
        var permissionGate = new PermissionGate();
        coreAgent.addLifecycle(new ServerPermissionLifecycle(
                sessionId,
                acpPermissionDispatcher(currentContext, permissionGate),
                permissionGate,
                autoApproveAll,
                whiteToolsPermissionStore(),
                toolName -> "builtin"));
        sessions.put(sessionId, new AcpSession(coreAgent, sessionId, currentContext, sessionPersistence));
        LOGGER.info("ACP session loaded: id={}, messages={}", sessionId, coreAgent.getMessages().size());
        return new AcpSchema.LoadSessionResponse(null, null);
    }

    private AcpSchema.PromptResponse handlePrompt(AcpSchema.PromptRequest req, SyncPromptContext promptCtx,
                                                    AgentContext ctx) {
        var acpSession = sessions.get(req.sessionId());
        if (acpSession == null) {
            LOGGER.warn("ACP prompt for unknown session: {}", req.sessionId());
            return AcpSchema.PromptResponse.refusal();
        }

        String promptText = req.text();
        if (promptText.isBlank()) {
            return AcpSchema.PromptResponse.refusal();
        }

        LOGGER.debug("ACP prompt: session={}, prompt={}", req.sessionId(),
                promptText.length() > 80 ? promptText.substring(0, 80) + "..." : promptText);

        if (promptText.startsWith("/")) {
            String commandResult = slashCommands.handle(promptText, acpSession,
                    ctx.result.llmProviders);
            if (commandResult != null) {
                promptCtx.sendUpdate(req.sessionId(),
                        new AcpSchema.AgentMessageChunk("agent_message_chunk",
                                new AcpSchema.TextContent(commandResult)));
                return AcpSchema.PromptResponse.endTurn();
            }
        }

        var outputRef = new AtomicReference<StringBuilder>(new StringBuilder());
        var thoughtRef = new AtomicReference<StringBuilder>(new StringBuilder());
        acpSession.agent().setStreamingCallback(new AcpStreamingCallback(promptCtx, req.sessionId(), outputRef, thoughtRef));
        acpSession.currentContext().set(promptCtx);

        // Reset cancellation flag so a cancelled session can continue with a new prompt.
        // Consistent with how InProcessAgentSession.sendMessage() handles cancellation.
        acpSession.agent().resetCancellation();

        try {
            String resultText = acpSession.agent().run(promptText);
            if (thoughtRef.get() != null && !thoughtRef.get().isEmpty()) {
                promptCtx.sendUpdate(req.sessionId(),
                        new AcpSchema.AgentThoughtChunk("agent_thought_chunk",
                                new AcpSchema.TextContent(thoughtRef.get().toString())));
                thoughtRef.set(null);
            }
            LOGGER.debug("ACP prompt completed: session={}, resultLength={}",
                    req.sessionId(), resultText.length());
            persistSession(acpSession);
            return AcpSchema.PromptResponse.endTurn();
        } catch (Exception e) {
            LOGGER.error("ACP prompt execution failed", e);
            promptCtx.sendMessage("Error: " + e.getMessage());
            persistSession(acpSession);
            return AcpSchema.PromptResponse.endTurn();
        }
    }

    private void persistSession(AcpSession acpSession) {
        if (acpSession.agent().hasPersistenceProvider()) {
            acpSession.agent().save(acpSession.sessionId());
            LOGGER.debug("ACP session persisted: {}", acpSession.sessionId());
        }
    }

    private ToolPermissionStore whiteToolsPermissionStore() {
        var permissionStore = new FileRuleBasedPermissionStore(
                workspace.resolve(".core-ai").resolve("tool-permissions.json"));
        permissionStore.allow(WriteTodosTool.WT_TOOL_NAME);
        permissionStore.allow(TaskTool.TOOL_NAME);
        permissionStore.allow(WebFetchTool.TOOL_NAME);
        permissionStore.allow(WebSearchTool.TOOL_NAME);
        permissionStore.allow(AskUserTool.TOOL_NAME);
        permissionStore.allow(MemoryTool.TOOL_NAME);
        permissionStore.allow(ReadFileTool.TOOL_NAME);
        permissionStore.allow(GlobFileTool.TOOL_NAME);
        permissionStore.allow(GrepFileTool.TOOL_NAME);
        return permissionStore;
    }

    private void restoreActiveProvider(PropertiesFileSource props, LLMProviders providers) {
        props.property("active.provider").ifPresent(name -> {
            var type = LLMProviderType.fromName(name);
            if (type != null && providers.getProvider(type) != null) {
                providers.setDefaultProvider(type);
            }
        });
    }

    private void registerMcpLoadingListener() {
        McpClientManagerRegistry.addCreationListener(manager ->
                manager.addListener((serverName, oldState, newState) -> {
                    if (newState == McpClientManager.ConnectionState.CONNECTING) {
                        LOGGER.info("Loading MCP server: {}", serverName);
                    } else if (newState == McpClientManager.ConnectionState.CONNECTED) {
                        LOGGER.info("MCP server loaded: {}", serverName);
                    } else if (newState == McpClientManager.ConnectionState.FAILED) {
                        LOGGER.warn("MCP server failed: {}", serverName);
                    }
                })
        );
    }

    private Consumer<AgentEvent> acpPermissionDispatcher(
            AtomicReference<SyncPromptContext> currentContext, PermissionGate permissionGate) {
        return event -> {
            if (event instanceof ToolApprovalRequestEvent approval) {
                SyncPromptContext ctx = currentContext.get();
                if (ctx != null) {
                    boolean allowed = ctx.askPermission("Allow tool: " + approval.toolName + "? Arguments: " + approval.arguments);
                    permissionGate.respond(approval.callId,
                            allowed ? ApprovalDecision.APPROVE : ApprovalDecision.DENY);
                } else {
                    LOGGER.warn("No ACP context available, denying tool: {}", approval.toolName);
                    permissionGate.respond(approval.callId, ApprovalDecision.DENY);
                }
            }
        };
    }

    private void broadcastAvailableCommands(String sessionId) {
        var acpAgent = acpAgentRef.get();
        if (acpAgent == null) return;
        var commands = slashCommands.listCommands().stream()
                .map(cmd -> {
                    var name = cmd.command().startsWith("/") ? cmd.command().substring(1) : cmd.command();
                    return new AvailableCommand(name, cmd.description(),
                            new AvailableCommandInput(""));
                })
                .toList();
        acpAgent.sendSessionUpdate(sessionId,
                new AvailableCommandsUpdate("available_commands_update", commands));
        LOGGER.debug("Broadcast {} available commands to session {}", commands.size(), sessionId);
    }

    private void mergeWorkspaceMcpConfig(PropertiesFileSource props) {
        Path mcpFile = workspace.resolve(".core-ai").resolve("MCP.json");
        if (!Files.exists(mcpFile)) {
            return;
        }
        try {
            String localJson = Files.readString(mcpFile);
            @SuppressWarnings("unchecked")
            var localServers = (Map<String, Object>) JsonUtil.fromJson(Map.class, localJson);
            if (localServers == null || localServers.isEmpty()) return;

            var globalJson = props.property("mcp.servers.json");
            Map<String, Object> merged;
            if (globalJson.isPresent()) {
                @SuppressWarnings("unchecked")
                var globalServers = (Map<String, Object>) JsonUtil.fromJson(Map.class, globalJson.get());
                merged = globalServers;
                merged.putAll(localServers);
            } else {
                merged = localServers;
            }
            props.putProperty("mcp.servers.json", JsonUtil.toJson(merged));
            LOGGER.info("merged workspace MCP config from {}: {} server(s)", mcpFile, merged.size());
        } catch (Exception e) {
            LOGGER.warn("failed to merge workspace MCP config from {}: {}", mcpFile, e.getMessage());
        }
    }

    private static void injectLiteLLMFallback(PropertiesFileSource props) {
        if (props.property("litellm.api.base").isPresent()) return;
        var auth = AuthConfig.load();
        if (auth != null && auth.apiKey() != null) {
            props.putProperty("litellm.api.base", auth.serverUrl() + "/api/litellm/v1");
            props.putProperty("litellm.api.key", auth.apiKey());
        }
    }

    private void registerAuthListener(BootstrapResult result) {
        var auth = AuthConfig.load();
        if (auth != null && auth.apiKey() != null) {
            RuntimeAuthConfig.instance().update(auth.serverUrl() + "/api/litellm/v1", auth.apiKey());
        }
        if (result.liteLLMProvider != null) {
            RuntimeAuthConfig.instance().addListener(() -> {
                var rt = RuntimeAuthConfig.instance();
                if (rt.isConfigured()) {
                    result.liteLLMProvider.updateCredentials(rt.serverUrl(), rt.apiKey());
                }
            });
        }
    }

    private record AgentContext(BootstrapResult result, boolean memoryEnabled,
                                 boolean dailyLogsEnabled, boolean coding,
                                 int maxTurn, List<A2ARemoteAgentConfig> remoteAgents,
                                 List<A2ARemoteServerConfig> remoteServers) {
    }
}
