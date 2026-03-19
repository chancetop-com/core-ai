package ai.core.cli;

import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.cli.agent.AgentSessionRunner;
import ai.core.cli.agent.CliAgent;
import ai.core.cli.config.InteractiveConfigSetup;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.log.CliLogger;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.remote.HttpAgentSession;
import ai.core.cli.remote.RemoteApiClient;
import ai.core.cli.remote.RemoteConfig;
import ai.core.cli.remote.RemoteSessionRunner;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence.SessionInfo;
import ai.core.session.FileRuleBasedPermissionStore;
import ai.core.session.ToolPermissionStore;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.MemoryTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.WriteTodosTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * @author stephen
 */
public class CliApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliApp.class);

    private static final Path DEFAULT_CONFIG = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");
    private static final String SESSIONS_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "sessions").toString();
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path configFile;
    private final String modelOverride;
    private final boolean autoApproveAll;
    private final boolean continueSession;
    private final boolean resume;
    private final Path workspace;

    public CliApp(Path configFile, String modelOverride, boolean autoApproveAll, boolean continueSession, boolean resume, Path workspace) {
        this.configFile = configFile != null ? configFile : DEFAULT_CONFIG;
        this.modelOverride = modelOverride;
        this.autoApproveAll = autoApproveAll;
        this.continueSession = continueSession;
        this.resume = resume;
        this.workspace = workspace != null ? workspace.toAbsolutePath() : Paths.get("").toAbsolutePath();
    }

    public void start() {
        // set core.appName before any core-ng class loads to suppress LogManager warning
        System.setProperty("core.appName", "core-ai-cli");

        InteractiveConfigSetup.setupIfNeeded();

        LOGGER.info("loading config from {}", configFile);
        var props = PropertiesFileSource.fromFile(configFile);
        var bootstrap = new AgentBootstrap(props);
        registerMcpLoadingListener();
        var result = bootstrap.initialize();
        clearLoading();
        LOGGER.info("bootstrap initialized");

        restoreActiveProvider(props, result.llmProviders);

        int maxTurn = props.property("agent.max.turn").map(Integer::parseInt).orElse(100);

        var sessionPersistence = new FileSessionPersistence(SESSIONS_DIR);
        var sessionManager = new SessionManager(sessionPersistence);
        var ui = new TerminalUI();
        var modelName = modelOverride != null ? modelOverride : result.llmProviders.getDefaultProvider().config.getModel();
        String currentSessionId = resolveOrCreateSessionId(sessionManager, ui);
        CliLogger.initialize(currentSessionId);
        var permissionStore = whiteToolsPermissionStore();
        var noteMemory = new MdMemoryProvider(workspace);
        var modelRegistry = new ModelRegistry(result.llmProviders, props);

        try {
            while (true) {
                var agentConfig = new CliAgent.Config(result.llmProviders, modelOverride, maxTurn, sessionPersistence, workspace, question -> {
                    ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "? " + AnsiTheme.RESET + question + "\n");
                    ui.printStreamingChunk(AnsiTheme.PROMPT + "  > " + AnsiTheme.RESET);
                    return ui.readRawLine();
                });
                var agent = CliAgent.of(agentConfig);
                var config = new AgentSessionRunner.Config(modelName, autoApproveAll, currentSessionId, sessionManager, permissionStore, noteMemory, modelRegistry);
                var runner = new AgentSessionRunner(ui, agent, result.llmProviders, config);
                String nextSessionId = runner.run();
                var remote = runner.getRemoteConfig();
                if (remote != null) {
                    runRemoteSession(ui, remote);
                    ui.printStreamingChunk(AnsiTheme.MUTED + "  Back to local mode." + AnsiTheme.RESET + "\n");
                    continue;
                }
                if (nextSessionId == null) break;
                currentSessionId = nextSessionId;
            }
            ui.printStreamingChunk("Goodbye!\n");
        } finally {
            CliLogger.close();
            closeQuietly(ui);
            closeShutdownResources(result);
        }
    }

    private ToolPermissionStore whiteToolsPermissionStore() {
        var permissionStore = new FileRuleBasedPermissionStore(workspace.resolve(".core-ai").resolve("tool-permissions.json"));
        permissionStore.allowDirs(List.of(
                ReadFileTool.TOOL_NAME,
                GlobFileTool.TOOL_NAME,
                GrepFileTool.TOOL_NAME
        ), workspace.toString());
        permissionStore.allow(WriteTodosTool.WT_TOOL_NAME);
        permissionStore.allow(AskUserTool.TOOL_NAME);
        permissionStore.allow(MemoryTool.TOOL_NAME);
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

    @SuppressWarnings("PMD.SystemPrintln")
    public void startRemote(String serverUrl, String apiKey, String agentId) {
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: --api-key is required for remote mode.");
            return;
        }
        var config = new RemoteConfig(serverUrl, apiKey, agentId != null ? agentId : "default-assistant", null);
        var ui = new TerminalUI();
        try {
            runRemoteSession(ui, config);
            ui.printStreamingChunk("Goodbye!\n");
        } catch (Exception e) {
            ui.showError(e.getMessage());
        } finally {
            closeQuietly(ui);
        }
    }

    private void runRemoteSession(TerminalUI ui, RemoteConfig config) {
        try {
            var api = new RemoteApiClient(config.serverUrl(), config.apiKey());
            var session = HttpAgentSession.connect(api, config.agentId());
            var runner = new RemoteSessionRunner(ui, session, api, config.name(), config.agentId());
            runner.run();
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

    @SuppressWarnings("PMD.SystemPrintln")
    private void clearLoading() {
        System.err.print("\r\033[K");
        System.err.flush();
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private void registerMcpLoadingListener() {
        McpClientManagerRegistry.addCreationListener(manager ->
                manager.addListener((serverName, oldState, newState) -> {
                    if (newState == McpClientManager.ConnectionState.CONNECTING) {
                        System.err.print("\r\033[K" + AnsiTheme.MUTED + "  Loading MCP server: " + serverName + "..." + AnsiTheme.RESET);
                        System.err.flush();
                    } else if (newState == McpClientManager.ConnectionState.CONNECTED) {
                        System.err.print("\r\033[K" + AnsiTheme.MUTED + "  MCP server loaded: " + serverName + AnsiTheme.RESET);
                        System.err.flush();
                    } else if (newState == McpClientManager.ConnectionState.FAILED) {
                        System.err.print("\r\033[K" + AnsiTheme.WARNING + "  MCP server failed: " + serverName + AnsiTheme.RESET);
                        System.err.flush();
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

}
