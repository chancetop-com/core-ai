package ai.core.cli;

import ai.core.a2a.A2ARunManager;
import ai.core.a2a.RemoteAgentSession;
import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.a2a.A2AServer;
import ai.core.cli.agent.AgentSessionRunner;
import ai.core.cli.agent.CliAgent;
import ai.core.cli.config.InteractiveConfigSetup;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.log.CliLogger;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.plugin.PluginManager;
import ai.core.cli.remote.A2ARemoteConnector;
import ai.core.cli.remote.RemoteConfig;
import ai.core.cli.remote.RemoteSessionRunner;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.utils.PathUtils;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.session.FileRuleBasedPermissionStore;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence.SessionInfo;
import ai.core.session.ToolPermissionStore;
import ai.core.cli.session.LocalChatSessionManager;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.MemoryTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.TaskTool;
import ai.core.tool.tools.WebFetchTool;
import ai.core.tool.tools.WebSearchTool;
import ai.core.tool.tools.WriteTodosTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CliApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliApp.class);

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path configFile;
    private final String modelOverride;
    private final String prompt;
    private final boolean autoApproveAll;
    private final boolean continueSession;
    private final boolean resume;
    private final Path workspace;

    public CliApp(CliAppOptions options) {
        this.configFile = options.configFile() != null ? options.configFile() : PathUtils.DEFAULT_CONFIG;
        this.modelOverride = options.modelOverride();
        this.prompt = options.prompt();
        this.autoApproveAll = options.autoApproveAll();
        this.continueSession = options.continueSession();
        this.resume = options.resume();
        this.workspace = options.workspace() != null ? options.workspace().toAbsolutePath() : Paths.get("").toAbsolutePath();
    }

    public void start() {
        System.setProperty("core.appName", "core-ai-cli");
        Path jarPath = PathUtils.getJarPath();
        Path configDir = configFile.getParent();
        PluginManager.getInstance(configDir).initializeIfNeeded(jarPath);

        var ui = new TerminalUI();
        InteractiveConfigSetup.setupIfNeeded(ui);

        var sessionContext = initializeSession(ui);
        if (prompt != null) {
            runSinglePrompt(ui, sessionContext, prompt);
        } else {
            runSessionLoop(ui, sessionContext);
        }
        cleanup(ui, sessionContext);
    }

    private SessionContext initializeSession(TerminalUI ui) {
        LOGGER.info("loading config from {}", configFile);
        var props = PropertiesFileSource.fromFile(configFile);
        var bootstrap = new AgentBootstrap(props);
        registerMcpLoadingListener();
        var result = bootstrap.initialize();
        clearLoading();
        LOGGER.info("bootstrap initialized");

        restoreActiveProvider(props, result.llmProviders);
        int maxTurn = props.property("agent.max.turn").map(Integer::parseInt).orElse(100);
        boolean memoryEnabled = props.property("agent.memory.enabled").map(Boolean::parseBoolean).orElse(false);
        boolean coding = props.property("agent.coding.enabled").map(Boolean::parseBoolean).orElse(false);
        var sessionPersistence = new FileSessionPersistence(PathUtils.sessionsDir(workspace));
        var sessionManager = new SessionManager(sessionPersistence);
        var modelName = modelOverride != null ? modelOverride : result.llmProviders.getDefaultProvider().config.getModel();
        String currentSessionId = resolveOrCreateSessionId(sessionManager, ui);
        CliLogger.initialize(currentSessionId);
        var permissionStore = whiteToolsPermissionStore();
        var noteMemory = memoryEnabled ? new MdMemoryProvider(workspace) : null;
        var modelRegistry = new ModelRegistry(result.llmProviders, props);
        return new SessionContext(result, props, maxTurn, sessionPersistence, sessionManager, modelName, currentSessionId, permissionStore, noteMemory, modelRegistry, memoryEnabled, coding);
    }

    private void runSessionLoop(TerminalUI ui, SessionContext ctx) {
        try {
            String currentSessionId = ctx.currentSessionId;
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
            var runner = createLocalRunner(ui, ctx, ctx.currentSessionId);
            runner.runPrompt(promptText);
        } finally {
            CliLogger.close();
        }
    }

    private AgentSessionRunner createLocalRunner(TerminalUI ui, SessionContext ctx, String sessionId) {
        var agentConfig = new CliAgent.Config(ctx.result.llmProviders, modelOverride, ctx.maxTurn, ctx.sessionPersistence, workspace, question -> {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "? " + AnsiTheme.RESET + question + "\n");
            ui.printStreamingChunk(AnsiTheme.PROMPT + "  > " + AnsiTheme.RESET);
            return ui.readRawLine();
        }, ctx.memoryEnabled, ctx.coding);
        var agent = CliAgent.of(agentConfig);
        var config = new AgentSessionRunner.Config(ctx.modelName, autoApproveAll, sessionId, ctx.sessionManager, ctx.permissionStore, ctx.noteMemory, ctx.modelRegistry, ctx.sessionPersistence, ctx.memoryEnabled);
        return new AgentSessionRunner(ui, agent, ctx.result.llmProviders, config);
    }

    private void cleanup(TerminalUI ui, SessionContext ctx) {
        closeQuietly(ui);
        closeShutdownResources(ctx.result);
    }

    private ToolPermissionStore whiteToolsPermissionStore() {
        var permissionStore = new FileRuleBasedPermissionStore(workspace.resolve(".core-ai").resolve("tool-permissions.json"));
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

    private String resolveOrCreateSessionId(SessionManager sessionManager, TerminalUI ui) {
        String sessionId = resolveSessionId(sessionManager, ui);
        return sessionId != null ? sessionId : "cli-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private String resolveSessionId(SessionManager sessionManager, TerminalUI ui) {
        if (continueSession) {
            var sessions = sessionManager.listSessions();
            if (sessions.isEmpty()) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "No previous sessions found. Starting new session." + AnsiTheme.RESET + "\n");
                return null;
            }
            var sessionId = sessions.getFirst().id();
            ui.printStreamingChunk(AnsiTheme.MUTED + "Resuming session: " + sessionId + AnsiTheme.RESET + "\n");
            return sessionId;
        }
        if (resume) {
            var sessions = sessionManager.listSessions();
            if (sessions.isEmpty()) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "No previous sessions found. Starting new session." + AnsiTheme.RESET + "\n");
                return null;
            }
            return pickSession(sessions, sessionManager, ui);
        }
        return null;
    }

    private String pickSession(List<SessionInfo> sessions, SessionManager sessionManager, TerminalUI ui) {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Recent sessions:" + AnsiTheme.RESET + "\n\n");
        int limit = Math.min(sessions.size(), 10);
        for (int i = 0; i < limit; i++) {
            var session = sessions.get(i);
            String timeStr = LocalDateTime.ofInstant(session.lastModified(), ZoneId.systemDefault()).format(DISPLAY_FORMAT);
            String title = truncate(sessionManager.firstUserMessage(session.id()), 50);
            ui.printStreamingChunk(String.format("  %s%2d)%s %s %s(%s)%s%n",
                    AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET,
                    title,
                    AnsiTheme.MUTED, timeStr, AnsiTheme.RESET));
        }
        ui.printStreamingChunk("\n");

        while (true) {
            ui.printStreamingChunk(AnsiTheme.PROMPT + "Select session (1-" + limit + "), or 'n' for new: " + AnsiTheme.RESET);
            var input = ui.readRawLine();
            if (input == null || "n".equalsIgnoreCase(input.trim())) {
                return null;
            }
            try {
                int choice = Integer.parseInt(input.trim());
                if (choice >= 1 && choice <= limit) {
                    var sessionId = sessions.get(choice - 1).id();
                    ui.printStreamingChunk(AnsiTheme.MUTED + "Resuming session: " + sessionId + AnsiTheme.RESET + "\n");
                    return sessionId;
                }
            } catch (NumberFormatException ignored) {
                // fall through to re-prompt
            }
            ui.printStreamingChunk(AnsiTheme.WARNING + "Invalid selection." + AnsiTheme.RESET + "\n");
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) return "(empty)";
        String cleaned = text.replaceAll("[\\r\\n]+", " ").strip();
        if (cleaned.length() <= maxLength) return cleaned;
        return cleaned.substring(0, maxLength) + "...";
    }

    public void startRemote(String serverUrl, String apiKey, String agentId) {
        var config = new RemoteConfig(serverUrl, apiKey, agentId != null ? agentId : "default-assistant", null);
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

        LOGGER.info("loading config from {}", configFile);
        var props = PropertiesFileSource.fromFile(configFile);
        var bootstrap = new AgentBootstrap(props);
        registerMcpLoadingListener();
        var result = bootstrap.initialize();
        clearLoading();
        LOGGER.info("bootstrap initialized for ACP serve mode");

        restoreActiveProvider(props, result.llmProviders);

        int maxTurn = props.property("agent.max.turn").map(Integer::parseInt).orElse(100);
        boolean memoryEnabled = props.property("agent.memory.enabled").map(Boolean::parseBoolean).orElse(true);
        boolean coding = props.property("agent.coding.enabled").map(Boolean::parseBoolean).orElse(false);
        var sessionPersistence = new FileSessionPersistence(PathUtils.sessionsDir(workspace));
        var sessionManager = new SessionManager(sessionPersistence);
        var permissionStore = whiteToolsPermissionStore();

        var currentSessionId = resolveSessionIdForServe(sessionManager);
        if (currentSessionId != null) {
            LOGGER.info("Resuming session: {}", currentSessionId);
        } else {
            currentSessionId = "serve-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            LOGGER.info("Starting new session: {}", currentSessionId);
        }

        CliLogger.initialize(currentSessionId);

        var agentConfig = new CliAgent.Config(result.llmProviders, modelOverride, maxTurn, sessionPersistence, workspace, question -> {
            LOGGER.info("agent asks user (auto-approved in serve mode): {}", question);
            return "(user input not available in web mode)";
        }, memoryEnabled, coding);

        var runManager = new A2ARunManager(() -> CliAgent.of(agentConfig), autoApproveAll, permissionStore, currentSessionId);
        var chatSessionManager = new LocalChatSessionManager(() -> CliAgent.of(agentConfig), autoApproveAll, permissionStore, sessionManager, sessionPersistence, workspace);
        var server = new A2AServer(port, runManager, chatSessionManager, sessionPersistence, webDir);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            closeShutdownResources(result);
        }));

        server.start();
        var url = "http://localhost:" + port;
        printServeStarted(url, currentSessionId, openBrowser);

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printServeStarted(String url, String currentSessionId, boolean openBrowser) {
        ConsoleWriter.println("A2A server running at " + url);
        if (!currentSessionId.startsWith("serve-")) {
            ConsoleWriter.println("Session: " + currentSessionId);
        }
        if (openBrowser) {
            BrowserLauncher.open(url);
        } else {
            ConsoleWriter.println("Headless mode - use any A2A client to connect");
        }
    }

    private String resolveSessionIdForServe(SessionManager sessionManager) {
        if (continueSession) {
            var sessions = sessionManager.listSessions();
            if (sessions.isEmpty()) {
                ConsoleWriter.println("No previous sessions found. Starting new session.");
                return null;
            }
            var sessionId = sessions.getFirst().id();
            ConsoleWriter.println("Resuming most recent session: " + sessionId);
            return sessionId;
        }
        if (resume) {
            var sessions = sessionManager.listSessions();
            if (sessions.isEmpty()) {
                ConsoleWriter.println("No previous sessions found. Starting new session.");
                return null;
            }
            ConsoleWriter.println("\nRecent sessions:");
            int limit = Math.min(sessions.size(), 10);
            for (int i = 0; i < limit; i++) {
                var session = sessions.get(i);
                String timeStr = LocalDateTime.ofInstant(session.lastModified(), ZoneId.systemDefault()).format(DISPLAY_FORMAT);
                String title = truncate(sessionManager.firstUserMessage(session.id()), 50);
                ConsoleWriter.printf("  %2d) %s (%s)%n", i + 1, title, timeStr);
            }
            ConsoleWriter.println();
            ConsoleWriter.print("Select session (1-" + limit + "), or 'n' for new: ");
            var scanner = new java.util.Scanner(System.in, StandardCharsets.UTF_8);
            while (true) {
                var input = scanner.nextLine();
                if ("n".equalsIgnoreCase(input.trim())) {
                    return null;
                }
                try {
                    int choice = Integer.parseInt(input.trim());
                    if (choice >= 1 && choice <= limit) {
                        var sessionId = sessions.get(choice - 1).id();
                        ConsoleWriter.println("Resuming session: " + sessionId);
                        return sessionId;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through to re-prompt
                }
                ConsoleWriter.println("Invalid selection. Enter 1-" + limit + " or 'n' for new: ");
            }
        }
        return null;
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

    private void closeQuietly(TerminalUI ui) {
        try {
            ui.close();
        } catch (Exception ignored) {
            // terminal cleanup failure is non-critical
        }
    }

    private void clearLoading() {
        ConsoleWriter.clearLine();
    }

    private void registerMcpLoadingListener() {
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

    private void closeShutdownResources(BootstrapResult result) {
        for (var resource : result.shutdownResources()) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // shutdown cleanup failure is non-critical
            }
        }
    }

    private record SessionContext(BootstrapResult result, PropertiesFileSource props, int maxTurn,
            FileSessionPersistence sessionPersistence, SessionManager sessionManager, String modelName,
            String currentSessionId, ToolPermissionStore permissionStore, MdMemoryProvider noteMemory,
            ModelRegistry modelRegistry, boolean memoryEnabled, boolean coding) { }

}
