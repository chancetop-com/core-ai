package ai.core.cli;

import ai.core.bootstrap.AgentBootstrap;
import ai.core.bootstrap.BootstrapResult;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.agent.AgentSessionRunner;
import ai.core.cli.agent.CliAgent;
import ai.core.cli.config.InteractiveConfigSetup;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.persistence.providers.FilePersistenceProvider;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * @author stephen
 */
public class CliApp {
    private static final Path DEFAULT_CONFIG = Path.of(System.getProperty("user.home"), ".core-ai-cli", "agent.properties");
    private static final String SESSIONS_DIR = Path.of(System.getProperty("user.home"), ".core-ai-cli", "sessions").toString();
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

        // suppress framework INFO logs before LoggerImpl class loads,
        // so its static STDOUT field captures the no-op stream
        var originalOut = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream(), false, java.nio.charset.StandardCharsets.UTF_8));

        InteractiveConfigSetup.setupIfNeeded();

        System.setOut(originalOut);
        DebugLog.log("loading config from " + configFile);

        System.setOut(new PrintStream(OutputStream.nullOutputStream(), false, java.nio.charset.StandardCharsets.UTF_8));

        var props = PropertiesFileSource.fromFile(configFile);
        var bootstrap = new AgentBootstrap(props);
        var result = bootstrap.initialize();

        System.setOut(originalOut);
        DebugLog.log("bootstrap initialized");

        int maxTurn = props.property("agent.max.turn").map(Integer::parseInt).orElse(100);

        var persistenceProvider = new FilePersistenceProvider(SESSIONS_DIR);
        var ui = new TerminalUI();
        var modelName = modelOverride != null ? modelOverride : result.llmProviders.getProvider().config.getModel();
        String currentSessionId = resolveSessionId(persistenceProvider, ui);
        if (currentSessionId == null) {
            currentSessionId = "cli-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        try {
            while (true) {
                var agent = CliAgent.of(result.llmProviders, modelOverride, maxTurn, persistenceProvider, workspace, question -> {
                    ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "? " + AnsiTheme.RESET + question + "\n");
                    ui.printStreamingChunk(AnsiTheme.PROMPT + "  > " + AnsiTheme.RESET);
                    return ui.readRawLine();
                });
                var config = new AgentSessionRunner.Config(modelName, autoApproveAll, currentSessionId, persistenceProvider);
                var runner = new AgentSessionRunner(ui, agent, result.llmProviders, config);
                String nextSessionId = runner.run();
                if (nextSessionId == null) break;
                currentSessionId = nextSessionId;
            }
            ui.printStreamingChunk("Goodbye!\n");
        } finally {
            closeQuietly(ui);
            closeShutdownResources(result);
        }
    }

    private String resolveSessionId(FilePersistenceProvider provider, TerminalUI ui) {
        if (continueSession) {
            var sessions = provider.listSessions();
            if (sessions.isEmpty()) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "No previous sessions found. Starting new session." + AnsiTheme.RESET + "\n");
                return null;
            }
            var sessionId = sessions.getFirst();
            ui.printStreamingChunk(AnsiTheme.MUTED + "Resuming session: " + sessionId + AnsiTheme.RESET + "\n");
            return sessionId;
        }
        if (resume) {
            var sessions = provider.listSessions();
            if (sessions.isEmpty()) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "No previous sessions found. Starting new session." + AnsiTheme.RESET + "\n");
                return null;
            }
            return pickSession(sessions, provider, ui);
        }
        return null;
    }

    private String pickSession(List<String> sessions, FilePersistenceProvider provider, TerminalUI ui) {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Recent sessions:" + AnsiTheme.RESET + "\n\n");
        int limit = Math.min(sessions.size(), 10);
        for (int i = 0; i < limit; i++) {
            var id = sessions.get(i);
            var filePath = Paths.get(provider.path(id));
            String timeStr = formatFileTime(filePath);
            ui.printStreamingChunk(String.format("  %s%2d)%s %s %s(%s)%s%n",
                AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET,
                id,
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
                    var sessionId = sessions.get(choice - 1);
                    ui.printStreamingChunk(AnsiTheme.MUTED + "Resuming session: " + sessionId + AnsiTheme.RESET + "\n");
                    return sessionId;
                }
            } catch (NumberFormatException ignored) {
                // fall through to re-prompt
            }
            ui.printStreamingChunk(AnsiTheme.WARNING + "Invalid selection." + AnsiTheme.RESET + "\n");
        }
    }

    private String formatFileTime(Path path) {
        try {
            var modified = Files.getLastModifiedTime(path).toInstant();
            return LocalDateTime.ofInstant(modified, ZoneId.systemDefault()).format(DISPLAY_FORMAT);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void closeQuietly(TerminalUI ui) {
        try {
            ui.close();
        } catch (Exception ignored) {
            // terminal cleanup failure is non-critical
        }
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
