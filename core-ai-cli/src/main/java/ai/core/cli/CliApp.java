package ai.core.cli;

import ai.core.a2a.A2ARunManager;
import ai.core.a2a.RemoteAgentSession;
import ai.core.agent.SubAgentConfig;
import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.a2a.A2AServer;
import ai.core.cli.acp.AcpAgentRunner;
import ai.core.cli.agent.AgentSessionRunner;
import ai.core.cli.agent.CliAgent;
import ai.core.cli.auth.AuthConfig;
import ai.core.cli.auth.AuthManager;
import ai.core.cli.auth.RuntimeAuthConfig;
import ai.core.cli.config.InteractiveConfigSetup;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.hook.ScriptHookLifecycle;
import ai.core.cli.log.CliLogger;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.plugin.PluginManager;
import ai.core.cli.remote.A2ARemoteAgentConfigLoader;
import ai.core.cli.remote.A2ARemoteConnector;
import ai.core.cli.remote.RemoteConfig;
import ai.core.cli.remote.RemoteSessionRunner;
import ai.core.cli.session.LocalChatSessionManager;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.utils.PathUtils;
import ai.core.llm.LLMProvider;
import ai.core.utils.JsonUtil;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.session.FileRuleBasedPermissionStore;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence.SessionInfo;
import ai.core.session.ToolPermissionStore;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.MemoryTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.TaskTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteTodoTaskTool;
import ai.core.tool.tools.WriteTodosTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CliApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliApp.class);
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SESSION_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void mergeWorkspaceConfig(PropertiesFileSource global, Path workspace) {
        Path localConfig = workspace.resolve(".core-ai").resolve("agent.properties");
        if (Files.exists(localConfig)) {
            try (var is = Files.newInputStream(localConfig)) {
                var localProps = new Properties();
                localProps.load(is);
                localProps.forEach((k, v) -> global.putProperty((String) k, (String) v));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load workspace-local config: " + localConfig, e);
            }
        }

        // Merge workspace MCP.json into mcp.servers.json so both
        // STDIO and HTTP MCP servers are auto-loaded on startup.
        mergeWorkspaceMcpConfig(global, workspace);
    }

    private static void mergeWorkspaceMcpConfig(PropertiesFileSource props, Path workspace) {
        Path mcpFile = resolveWorkspaceMcpFile(workspace);
        if (!Files.exists(mcpFile)) return;
        try {
            String localJson = Files.readString(mcpFile);
            @SuppressWarnings("unchecked")
            var parsedLocal = (Map<String, Object>) JsonUtil.fromJson(Map.class, localJson);
            var localServers = normalizeMcpServers(parsedLocal);
            if (localServers == null || localServers.isEmpty()) return;

            var globalJson = props.property("mcp.servers.json");
            Map<String, Object> merged;
            if (globalJson.isPresent()) {
                @SuppressWarnings("unchecked")
                var parsedGlobal = (Map<String, Object>) JsonUtil.fromJson(Map.class, globalJson.get());
                var globalServers = normalizeMcpServers(parsedGlobal);
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

    private static Path resolveWorkspaceMcpFile(Path workspace) {
        var coreAiDir = workspace.resolve(".core-ai");
        var canonical = coreAiDir.resolve("MCP.json");
        if (Files.exists(canonical)) return canonical;
        return coreAiDir.resolve("mcp.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeMcpServers(Map<String, Object> config) {
        if (config == null || config.isEmpty()) return config;
        var mcpServers = config.get("mcpServers");
        if (mcpServers instanceof Map<?, ?> servers) {
            return (Map<String, Object>) servers;
        }
        return config;
    }

    /**
     * If {@code litellm.api.base} is not in agent.properties, inject fallback
     * values from saved auth credentials so the server's LiteLLM proxy is used
     * without manual configuration.
     */
    private static void injectLiteLLMFallback(PropertiesFileSource props) {
        if (props.property("litellm.api.base").isPresent()) return;
        var auth = AuthConfig.load();
        if (auth != null && auth.apiKey() != null) {
            props.putProperty("litellm.api.base", auth.serverUrl() + "/api/litellm/v1");
            props.putProperty("litellm.api.key", auth.apiKey());
        }
    }

    /**
     * Populate RuntimeAuthConfig from saved auth and register a listener that
     * updates the LiteLLM provider when credentials change (login / server-switch).
     */
    private void registerAuthListener(BootstrapResult result) {
        var auth = AuthConfig.load();
        if (auth != null && auth.apiKey() != null) {
            RuntimeAuthConfig.instance().update(auth.serverUrl() + "/api/litellm/v1", auth.apiKey());
        }
        if (result.liteLLMProvider != null) {
            RuntimeAuthConfig.instance().addListener(() -> {
                var rt = RuntimeAuthConfig.instance();
                if (rt.isConfigured()) {
                    // rt.serverUrl() already includes /api/litellm/v1 from AuthManager
                    result.liteLLMProvider.updateCredentials(rt.serverUrl(), rt.apiKey());
                }
            });
        }
    }

    private static Map<String, SubAgentConfig> parseSubAgentConfig(PropertiesFileSource props, LLMProviders llmProviders) {
        Map<String, SubAgentConfig> configs = new HashMap<>();
        String prefix = "agent.sub.";
        for (String key : props.propertyNames()) {
            if (!key.startsWith(prefix)) continue;
            String suffix = key.substring(prefix.length());
            String agentName;
            if (suffix.endsWith(".model")) {
                agentName = suffix.substring(0, suffix.length() - ".model".length());
                props.property(key).ifPresent(model -> configs.computeIfAbsent(agentName, k -> new SubAgentConfig()).model(model));
            } else if (suffix.endsWith(".provider")) {
                agentName = suffix.substring(0, suffix.length() - ".provider".length());
                props.property(key).ifPresent(providerName -> {
                    var provider = resolveProvider(providerName, llmProviders);
                    if (provider != null) {
                        configs.computeIfAbsent(agentName, k -> new SubAgentConfig()).llmProvider(provider);
                    }
                });
            } else if (suffix.endsWith(".max-turn")) {
                agentName = suffix.substring(0, suffix.length() - ".max-turn".length());
                props.property(key).map(Integer::parseInt).ifPresent(maxTurn ->
                        configs.computeIfAbsent(agentName, k -> new SubAgentConfig()).maxTurnNumber(maxTurn));
            }
        }
        return configs;
    }

    private static LLMProvider resolveProvider(String name, LLMProviders llmProviders) {
        try {
            var type = LLMProviderType.fromName(name);
            return llmProviders.getProvider(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ToolPermissionStore whiteToolsPermissionStore(Path workspace) {
        var permissionStore = new FileRuleBasedPermissionStore(workspace.resolve(".core-ai").resolve("tool-permissions.json"));
        permissionStore.allow(WriteTodosTool.WT_TOOL_NAME);
        permissionStore.allow(WriteTodoTaskTool.TOOL_NAME_CREATE);
        permissionStore.allow(WriteTodoTaskTool.TOOL_NAME_UPDATE);
        permissionStore.allow(WriteTodoTaskTool.TOOL_NAME_LIST);
        permissionStore.allow(WriteTodoTaskTool.TOOL_NAME_GET);
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
    private static String defaultSessionId(String prefix) {
        return prefix + LocalDateTime.now().format(SESSION_ID_FORMAT);
    }
    private static String pickSession(List<SessionInfo> sessions, SessionManager sessionManager,
                                       Consumer<String> output, Supplier<String> input) {
        output.accept("\nRecent sessions:\n\n");
        int limit = Math.min(sessions.size(), 10);
        for (int i = 0; i < limit; i++) {
            var session = sessions.get(i);
            String timeStr = LocalDateTime.ofInstant(session.lastModified(), ZoneId.systemDefault()).format(DISPLAY_FORMAT);
            String title = truncate(sessionManager.firstUserMessage(session.id()), 50);
            output.accept(String.format("  %2d) %s (%s)%n", i + 1, title, timeStr));
        }
        output.accept("\n");
        while (true) {
            output.accept("Select session (1-" + limit + "), or 'n' for new: ");
            var choice = input.get();
            if (choice == null || "n".equalsIgnoreCase(choice.trim())) return null;
            try {
                int idx = Integer.parseInt(choice.trim());
                if (idx >= 1 && idx <= limit) return sessions.get(idx - 1).id();
            } catch (NumberFormatException ignored) {
                // fall through to re-prompt
            }
            output.accept("Invalid selection.\n");
        }
    }
    private static String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) return "(empty)";
        String cleaned = text.replaceAll("[\\r\\n]+", " ").strip();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength) + "...";
    }

    private static void closeQuietly(TerminalUI ui) {
        try {
            ui.close();
        } catch (Exception ignored) {
            // terminal cleanup failure is non-critical
        }
    }
    private static void registerMcpLoadingListener() {
        McpClientManagerRegistry.addCreationListener(manager ->
                manager.addListener((serverName, oldState, newState) -> {
                    if (newState == McpClientManager.ConnectionState.CONNECTING) {
                        ConsoleWriter.clearLineAndPrint(AnsiTheme.MUTED + "  Loading MCP server: " + serverName + "..." + AnsiTheme.RESET);
                    } else if (newState == McpClientManager.ConnectionState.CONNECTED) {
                        ConsoleWriter.clearLineAndPrint(AnsiTheme.MUTED + "  MCP server loaded: " + serverName + AnsiTheme.RESET);
                    } else if (newState == McpClientManager.ConnectionState.FAILED) {
                        ConsoleWriter.clearLineAndPrint(AnsiTheme.WARNING + "  MCP server failed: " + serverName + AnsiTheme.RESET);
                    }
                })
        );
    }

    private static void closeShutdownResources(BootstrapResult result) {
        for (var resource : result.shutdownResources()) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // shutdown cleanup failure is non-critical
            }
        }
    }

    private final Path configFile;
    private final String modelOverride;
    private final String prompt;
    private final boolean autoApproveAll;
    private final boolean continueSession;
    private final boolean resume;
    private final Path workspace;
    private final Integer timeLimitSeconds;

    public CliApp(CliAppOptions options) {
        this.configFile = options.configFile() != null ? options.configFile() : PathUtils.DEFAULT_CONFIG;
        this.modelOverride = options.modelOverride();
        this.prompt = options.prompt();
        this.autoApproveAll = options.autoApproveAll();
        this.continueSession = options.continueSession();
        this.resume = options.resume();
        this.workspace = options.workspace() != null ? options.workspace().toAbsolutePath() : Paths.get("").toAbsolutePath();
        this.timeLimitSeconds = options.timeLimitSeconds();
    }

    private BootstrapCore bootstrapCore() {
        PropertiesFileSource props;
        if (Files.exists(configFile)) {
            LOGGER.info("loading config from {}", configFile);
            props = PropertiesFileSource.fromFile(configFile);
        } else {
            LOGGER.info("no config file at {}, using defaults", configFile);
            props = new PropertiesFileSource(new Properties());
        }
        mergeWorkspaceConfig(props, workspace);

        // Inject LiteLLM fallback from saved auth credentials so logged-in
        // users can call the server's LiteLLM proxy without configuring
        // provider properties in agent.properties.
        injectLiteLLMFallback(props);

        var bootstrap = new AgentBootstrap(props);
        registerMcpLoadingListener();
        var result = bootstrap.initialize();
        ConsoleWriter.clearLine();
        LOGGER.info("bootstrap initialized");

        // Register listener so LiteLLM provider is updated on login / server-switch.
        registerAuthListener(result);
        props.property("active.provider").ifPresent(name -> {
            var type = LLMProviderType.fromName(name);
            if (type != null && result.llmProviders.getProvider(type) != null) {
                result.llmProviders.setDefaultProvider(type);
            }
        });
        int maxTurn = props.property("agent.max.turn").map(Integer::parseInt).orElse(100);
        boolean memory = props.property("agent.memory.enabled").map(Boolean::parseBoolean).orElse(false);
        boolean dailyLogs = props.property("agent.memory.daily.logs.enabled").map(Boolean::parseBoolean).orElse(false);
        boolean coding = props.property("agent.coding.enabled").map(Boolean::parseBoolean).orElse(false);
        boolean todoV2 = props.property("agent.todo.v2.enabled").map(Boolean::parseBoolean).orElse(false);
        props.property("agent.memory.timezone").map(ZoneId::of).ifPresent(MemoryTriggerService::setTimezone);
        var remoteAgents = A2ARemoteAgentConfigLoader.load(props);
        var remoteServers = A2ARemoteAgentConfigLoader.loadServers(props);
        var sessionPersistence = new FileSessionPersistence(PathUtils.sessionsDir(workspace));
        var sessionManager = new SessionManager(sessionPersistence);
        var permissionStore = whiteToolsPermissionStore(workspace);
        var subAgentConfigs = parseSubAgentConfig(props, result.llmProviders);
        boolean a2aAutoDiscover = props.property("a2a.autoDiscover").map(Boolean::parseBoolean).orElse(false);
        return new BootstrapCore(props, result, maxTurn, memory, dailyLogs, coding, todoV2,
                remoteAgents, remoteServers, sessionPersistence, sessionManager, permissionStore, subAgentConfigs, a2aAutoDiscover);
    }

    private String resolveSessionId(SessionManager sessionManager, TerminalUI ui) {
        if (continueSession) {
            var sessions = sessionManager.listSessions();
            if (sessions.isEmpty()) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "No previous sessions found. Starting new session." + AnsiTheme.RESET + "\n");
                return null;
            }
            var sid = sessions.getFirst().id();
            ui.printStreamingChunk(AnsiTheme.MUTED + "Resuming session: " + sid + AnsiTheme.RESET + "\n");
            return sid;
        }
        if (resume) {
            var sessions = sessionManager.listSessions();
            if (sessions.isEmpty()) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "No previous sessions found. Starting new session." + AnsiTheme.RESET + "\n");
                return null;
            }
            return pickSession(sessions, sessionManager, ui::printStreamingChunk, ui::readRawLine);
        }
        return null;
    }

    private String resolveSessionIdForServe(SessionManager sessionManager) {
        if (continueSession) {
            var sessions = sessionManager.listSessions();
            if (sessions.isEmpty()) {
                ConsoleWriter.println("No previous sessions found. Starting new session.");
                return null;
            }
            var sid = sessions.getFirst().id();
            ConsoleWriter.println("Resuming most recent session: " + sid);
            return sid;
        }
        if (resume) {
            var sessions = sessionManager.listSessions();
            if (sessions.isEmpty()) {
                ConsoleWriter.println("No previous sessions found. Starting new session.");
                return null;
            }
            return pickSession(sessions, sessionManager, ConsoleWriter::println, () -> new java.util.Scanner(System.in, StandardCharsets.UTF_8).nextLine());
        }
        return null;
    }

    public void startAcpAgent() {
        System.setProperty("core.appName", "core-ai-cli");
        var runner = new AcpAgentRunner(configFile, modelOverride, autoApproveAll, workspace);
        runner.run();
    }

    public void start() {
        System.setProperty("core.appName", "core-ai-cli");
        Path jarPath = PathUtils.getJarPath();
        Path configDir = configFile.getParent();
        PluginManager.getInstance(configDir).initializeIfNeeded(jarPath);
        var ui = new TerminalUI();
        InteractiveConfigSetup.setupIfNeeded(ui);
        var sessionContext = initializeSession(ui);
        var shutdownResources = sessionContext.result().shutdownResources();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ScriptHookLifecycle.fireSessionStopHooks(workspace);
            for (var resource : shutdownResources) {
                try {
                    resource.close();
                } catch (Exception ignored) {
                    // shutdown cleanup failure is non-critical
                }
            }
            closeQuietly(ui);
        }, "cli-shutdown-hook"));
        if (prompt != null) {
            runSinglePrompt(ui, sessionContext, prompt);
        } else {
            runSessionLoop(ui, sessionContext);
        }
        closeQuietly(ui);
        closeShutdownResources(sessionContext.result());
    }

    private SessionContext initializeSession(TerminalUI ui) {
        var bc = bootstrapCore();
        var modelName = modelOverride != null ? modelOverride : bc.result().llmProviders.getDefaultProvider().config.getModel();
        String currentSessionId = resolveSessionId(bc.sessionManager(), ui);
        if (currentSessionId == null) currentSessionId = defaultSessionId("cli-");
        CliLogger.initialize(workspace, currentSessionId);
        var noteMemory = bc.memoryEnabled() ? new MdMemoryProvider(workspace) : null;
        var modelRegistry = new ModelRegistry(bc.result().llmProviders, bc.props());
        boolean promptExtraction = bc.props().property("agent.memory.prompt.extraction").map(Boolean::parseBoolean).orElse(false);
        return new SessionContext(bc.result(), bc.props(), bc.maxTurn(), bc.sessionPersistence(), bc.sessionManager(), modelName,
                currentSessionId, bc.permissionStore(), noteMemory, modelRegistry, bc.memoryEnabled(), bc.dailyLogsEnabled(),
                bc.coding(), bc.todoV2Enabled(), bc.remoteAgents(), bc.remoteServers(), bc.subAgentConfigs(), promptExtraction, timeLimitSeconds, bc.a2aAutoDiscover());
    }

    private void runSessionLoop(TerminalUI ui, SessionContext ctx) {
        try {
            printAuthBanner(ui);
            String currentSessionId = ctx.currentSessionId();
            while (true) {
                var runner = createLocalRunner(ui, ctx, currentSessionId);
                String nextSessionId = runner.run();
                var remote = runner.getRemoteConfig();
                if (remote != null) {
                    runRemoteSession(ui, remote, null);
                    ui.printStreamingChunk(AnsiTheme.MUTED + "  Back to local mode." + AnsiTheme.RESET + "\n");
                    continue;
                }
                if (nextSessionId == null) break;
                currentSessionId = nextSessionId.isEmpty() ? null : nextSessionId;
            }
            ui.printStreamingChunk("Goodbye!\n");
        } finally {
            CliLogger.close();
        }
    }

    private void runSinglePrompt(TerminalUI ui, SessionContext ctx, String promptText) {
        try {
            var runner = createLocalRunner(ui, ctx, ctx.currentSessionId());
            runner.runPrompt(promptText);
        } finally {
            CliLogger.close();
        }
    }

    private AgentSessionRunner createLocalRunner(TerminalUI ui, SessionContext ctx, String sessionId) {
        var agentConfig = new CliAgent.Config(ctx.result().llmProviders, modelOverride, ctx.maxTurn(), ctx.sessionPersistence(), workspace, question -> {
            return ui.readRawLine("\n  " + AnsiTheme.WARNING + "? " + AnsiTheme.RESET + question + "\n" + AnsiTheme.PROMPT + "  > " + AnsiTheme.RESET);
        }, ctx.memoryEnabled(), ctx.dailyLogsEnabled(), ctx.coding(), ctx.todoV2Enabled(), sessionId, ctx.remoteAgents(), ctx.remoteServers(), ctx.subAgentConfigs(), ctx.a2aAutoDiscover());
        var agent = CliAgent.of(agentConfig);
        var defaultServerUrl = ctx.props().property("core.server.url").orElse(null);
        var config = new AgentSessionRunner.Config(ctx.modelName(), autoApproveAll, sessionId, ctx.sessionManager(), ctx.permissionStore(), ctx.noteMemory(), ctx.modelRegistry(), ctx.sessionPersistence(), ctx.memoryEnabled(), ctx.dailyLogsEnabled(), ctx.promptExtractionEnabled(), ctx.timeLimitSeconds(), defaultServerUrl);
        return new AgentSessionRunner(ui, agent, ctx.result().llmProviders, config);
    }

    public void startRemote(String serverUrl, String apiKey, String agentId) {
        // Save credentials so A2ARemoteConnector can resolve them via AuthService
        if (apiKey != null) {
            AuthConfig.login(serverUrl, apiKey).save();
            RuntimeAuthConfig.instance().update(serverUrl + "/api/litellm/v1", apiKey);
        }
        var config = new RemoteConfig(serverUrl, agentId != null ? agentId : "default-assistant", null);
        var ui = new TerminalUI();
        try {
            runRemoteSession(ui, config, prompt);
            ui.printStreamingChunk("Goodbye!\n");
        } catch (Exception e) {
            ui.showError(e.getMessage());
        } finally {
            closeQuietly(ui);
        }
    }

    public void startServe(int port, boolean openBrowser, Path webDir) {
        System.setProperty("core.appName", "core-ai-cli");
        var bc = bootstrapCore();
        var sessionManager = bc.sessionManager();
        var currentSessionId = resolveSessionIdForServe(sessionManager);
        if (currentSessionId == null) currentSessionId = defaultSessionId("serve-");
        CliLogger.initialize(workspace, currentSessionId);
        var agentConfig = new CliAgent.Config(bc.result().llmProviders, modelOverride, bc.maxTurn(), bc.sessionPersistence(), workspace, question -> {
            LOGGER.info("agent asks user (auto-approved in serve mode): {}", question);
            return "(user input not available in web mode)";
        }, bc.memoryEnabled(), bc.dailyLogsEnabled(), bc.coding(), bc.todoV2Enabled(), currentSessionId, bc.remoteAgents(), bc.remoteServers(), bc.subAgentConfigs(), bc.a2aAutoDiscover());
        var runManager = new A2ARunManager(() -> CliAgent.of(agentConfig), autoApproveAll, bc.permissionStore(), currentSessionId);
        var chatSessionManager = new LocalChatSessionManager(() -> CliAgent.of(agentConfig), autoApproveAll, bc.permissionStore(), sessionManager, bc.sessionPersistence(), workspace);
        var server = new A2AServer(port, runManager, chatSessionManager, bc.sessionPersistence(), webDir);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ScriptHookLifecycle.fireSessionStopHooks(workspace);
            server.stop();
            closeShutdownResources(bc.result());
        }));
        server.start();
        var authStatus = AuthManager.status();
        if (authStatus != null) ConsoleWriter.println("[" + authStatus + "]");
        var url = "http://localhost:" + port;
        ConsoleWriter.println("A2A server running at " + url);
        if (!currentSessionId.startsWith("serve-")) {
            ConsoleWriter.println("Session: " + currentSessionId);
        }
        if (openBrowser) {
            BrowserLauncher.open(url);
        } else {
            ConsoleWriter.println("Headless mode - use any A2A client to connect");
        }
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runRemoteSession(TerminalUI ui, RemoteConfig config, String promptText) {
        try {
            var connection = new A2ARemoteConnector().connect(config);
            var session = RemoteAgentSession.connect(connection.client());
            var name = config.name() != null ? config.name() : connection.agentName();
            var runner = new RemoteSessionRunner(ui, session, connection.baseUrl(), name, connection.agentId());
            if (promptText != null) {
                runner.runPrompt(promptText);
            } else {
                runner.run();
            }
        } catch (Exception e) {
            ui.showError(e.getMessage());
        }
    }

    private void printAuthBanner(TerminalUI ui) {
        if (AuthManager.isLoggedIn()) {
            var status = AuthManager.status();
            ui.printStreamingChunk(AnsiTheme.MUTED + "  [" + status + "]" + AnsiTheme.RESET + "\n");
        }
    }
}
