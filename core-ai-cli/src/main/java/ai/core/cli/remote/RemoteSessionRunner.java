package ai.core.cli.remote;

import ai.core.api.server.session.AgentSession;
import ai.core.cli.DebugLog;
import ai.core.cli.command.SlashCommand;
import ai.core.cli.listener.RemoteEventListener;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.TerminalUI;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * @author stephen
 */
public class RemoteSessionRunner {
    private static final String POISON_PILL = "\0__EXIT__";

    private static final List<SlashCommand> REMOTE_COMMANDS = List.of(
        new SlashCommand("/help", "Show available commands"),
        new SlashCommand("/build-agent", "Build agent from conversation"),
        new SlashCommand("/agents", "Switch agent or manage agents"),
        new SlashCommand("/tools", "List and load available tools"),
        new SlashCommand("/skill", "Browse and load server skills"),
        new SlashCommand("/mcp", "Manage MCP server connections"),
        new SlashCommand("/debug", "Toggle debug mode"),
        new SlashCommand("/clear", "Clear screen"),
        new SlashCommand("/exit", "Disconnect and return to local mode")
    );
    private static final List<SlashCommand> A2A_REMOTE_COMMANDS = List.of(
        new SlashCommand("/help", "Show available commands"),
        new SlashCommand("/debug", "Toggle debug mode"),
        new SlashCommand("/clear", "Clear screen"),
        new SlashCommand("/exit", "Disconnect and return to local mode")
    );

    static String truncate(String text, int max) {
        if (text == null) return "";
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    static String buildPromptPrefix(String name, String serverUrl) {
        String host = URI.create(serverUrl).getHost();
        if (host == null || host.isBlank()) host = serverUrl;
        if (name != null && !name.isBlank()) {
            return name + "@" + host;
        }
        return host;
    }

    private final TerminalUI ui;
    private final RemoteApiClient api;
    private final String serverUrl;
    private final String promptPrefix;
    private volatile AgentSession session;
    private volatile RemoteEventListener listener;
    private volatile String currentPrompt;
    private final Set<String> loadedToolIds = new HashSet<>();
    private final Set<String> loadedSkillIds = new HashSet<>();
    private final Set<String> loadedSubAgentIds = new HashSet<>();
    private final RemoteSessionCommandHandler commandHandler;

    public RemoteSessionRunner(TerminalUI ui, HttpAgentSession session, RemoteApiClient api, String name, String agentId) {
        this(ui, session, api, api.serverUrl(), name, agentId);
    }

    public RemoteSessionRunner(TerminalUI ui, AgentSession session, String serverUrl, String name, String agentId) {
        this(ui, session, null, serverUrl, name, agentId);
    }

    private RemoteSessionRunner(TerminalUI ui, AgentSession session, RemoteApiClient api, String serverUrl, String name, String agentId) {
        this.ui = ui;
        this.api = api;
        this.serverUrl = serverUrl;
        this.promptPrefix = buildPromptPrefix(name, serverUrl);
        this.session = session;
        this.listener = new RemoteEventListener(ui, session);
        this.currentPrompt = promptPrefix + ":" + (agentId != null ? agentId : "agent") + "> ";
        this.commandHandler = api != null
                ? new RemoteSessionCommandHandler(ui, api, session.id(), loadedToolIds, loadedSkillIds, loadedSubAgentIds)
                : null;
    }

    public void run() {
        session.onEvent(listener);

        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);

        ui.setSlashCommands(commandHandler != null ? REMOTE_COMMANDS : A2A_REMOTE_COMMANDS);
        try {
            printBanner();
            startSenderThread(messageQueue, readyForInput);
            readInputLoop(messageQueue, readyForInput);
            session.close();
        } finally {
            ui.resetSlashCommands();
        }
    }

    public void runPrompt(String prompt) {
        session.onEvent(listener);
        printBanner();
        if (prompt != null && !prompt.isBlank()) {
            ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + currentPrompt + AnsiTheme.RESET + prompt.strip() + "\n");
        }
        listener.prepareTurn();
        session.sendMessage(FileReferenceExpander.expand(prompt == null ? "" : prompt));
        listener.waitForTurn();
        session.close();
    }

    private void switchAgent(String agentId, String agentName) {
        if (api == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Agent switching is not available for generic A2A sessions." + AnsiTheme.RESET + "\n");
            return;
        }
        session.close();
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Switching to " + agentName + "..." + AnsiTheme.RESET + "\n");
        try {
            session = HttpAgentSession.connect(api, agentId);
            listener = new RemoteEventListener(ui, session);
            session.onEvent(listener);
            currentPrompt = promptPrefix + ":" + agentName + "> ";
            loadedToolIds.clear();
            loadedSkillIds.clear();
            loadedSubAgentIds.clear();
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Session: " + session.id() + AnsiTheme.RESET + "\n\n");
        } catch (RuntimeException e) {
            ui.showError("failed to switch agent: " + e.getMessage());
        }
    }

    private void printBanner() {
        ui.printStreamingChunk("\n" + AnsiTheme.WARNING + "  [REMOTE]" + AnsiTheme.RESET + " "
                + AnsiTheme.PROMPT + "core-ai" + AnsiTheme.RESET
                + AnsiTheme.MUTED + " → " + serverUrl + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  session: " + session.id() + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  /help for commands, /exit to return to local mode" + AnsiTheme.RESET + "\n");
    }

    private void startSenderThread(BlockingQueue<String> queue, Semaphore readyForInput) {
        Thread senderThread = new Thread(() -> {
            try {
                while (true) {
                    String msg = queue.take();
                    if (POISON_PILL.equals(msg)) break;
                    DebugLog.log("sending message: " + msg);
                    listener.prepareTurn();
                    session.sendMessage(msg);
                    DebugLog.log("waiting for turn...");
                    listener.waitForTurn();
                    DebugLog.log("turn finished");
                    readyForInput.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sender-thread");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private void readInputLoop(BlockingQueue<String> queue, Semaphore readyForInput) {
        boolean showFrame = true;
        while (true) {
            waitForReady(readyForInput);
            if (showFrame) ui.printInputFrame();
            var input = ui.readInput(currentPrompt);
            if (input == null || "/exit".equalsIgnoreCase(input.trim())) {
                queue.offer(POISON_PILL);
                break;
            }
            if (input.isBlank()) {
                showFrame = false;
                readyForInput.release();
                continue;
            }
            var trimmed = input.trim();
            if (trimmed.startsWith("/") && handleCommand(trimmed)) {
                showFrame = true;
                readyForInput.release();
                continue;
            }
            showFrame = true;
            queue.offer(FileReferenceExpander.expand(input));
        }
    }

    private boolean handleCommand(String cmd) {
        var lower = cmd.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "/help" -> printHelp();
            case "/build-agent" -> handleBuildAgent(cmd);
            case "/agents" -> handleAgents(cmd);
            case "/tools" -> handleTools(cmd);
            case "/skill" -> handleSkills(cmd);
            case "/mcp" -> handleMcp(cmd);
            case "/debug" -> handleDebug();
            case "/clear" -> ui.printStreamingChunk("\u001B[2J\u001B[H");
            default -> {
                if (lower.startsWith("/stats") || lower.startsWith("/model")
                        || lower.startsWith("/undo") || lower.startsWith("/compact") || lower.startsWith("/export")
                        || lower.startsWith("/copy") || lower.startsWith("/resume") || lower.startsWith("/memory")) {
                    ui.printStreamingChunk(AnsiTheme.MUTED + "  Not available in remote mode.\n" + AnsiTheme.RESET);
                    break;
                }
                ui.printStreamingChunk(AnsiTheme.WARNING + "Unknown command: " + cmd.split("\\s+", 2)[0] + ". Type /help for available commands." + AnsiTheme.RESET + "\n");
            }
        }
        return true;
    }

    private void handleBuildAgent(String cmd) {
        if (commandHandler != null) commandHandler.handleBuildAgent();
        else printA2ACommandUnavailable(cmd);
    }

    private void handleAgents(String cmd) {
        if (commandHandler == null) {
            printA2ACommandUnavailable(cmd);
            return;
        }
        var action = commandHandler.handleAgentSwitch();
        if (action instanceof AgentCommandHandler.AgentSwitch(String agentId, String agentName)) {
            switchAgent(agentId, agentName);
        } else if (action instanceof AgentCommandHandler.LoadAsSubAgent s) {
            commandHandler.loadSubAgentToSession(s.agentId());
        }
    }

    private void handleTools(String cmd) {
        if (commandHandler != null) commandHandler.handleTools();
        else printA2ACommandUnavailable(cmd);
    }

    private void handleSkills(String cmd) {
        if (commandHandler != null) commandHandler.handleSkills();
        else printA2ACommandUnavailable(cmd);
    }

    private void handleMcp(String cmd) {
        if (api != null) new McpServerCommandHandler(ui, api).handle();
        else printA2ACommandUnavailable(cmd);
    }

    private void handleDebug() {
        if (DebugLog.isEnabled()) {
            DebugLog.disable();
            ui.printStreamingChunk("Debug mode: OFF\n");
        } else {
            DebugLog.enable();
            System.setProperty("core.ai.debug", "true");
            ui.printStreamingChunk("Debug mode: ON\n");
        }
    }

    private void printA2ACommandUnavailable(String cmd) {
        ui.printStreamingChunk(AnsiTheme.MUTED + "  " + cmd + " is not part of the generic A2A session protocol.\n" + AnsiTheme.RESET);
    }

    private void printHelp() {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Remote Commands:" + AnsiTheme.RESET + "\n");
        String[][] cmds = commandHandler != null ? new String[][]{
                {"/build-agent", "Build agent from current conversation, so it can be scheduled periodically on the server"},
                {"/agents", "Switch agent or manage agents"},
                {"/tools", "List and load available tools"},
                {"/skill", "Browse and load server skills"},
                {"/mcp", "Manage MCP server connections (add, enable, disable, edit, delete)"},
                {"/debug", "Toggle debug mode"},
                {"/clear", "Clear screen"},
                {"/exit", "Disconnect and return to local mode"}
        } : new String[][]{
                {"/debug", "Toggle debug mode"},
                {"/clear", "Clear screen"},
                {"/exit", "Disconnect and return to local mode"}
        };
        for (var c : cmds) {
            ui.printStreamingChunk(String.format("  %s%-16s%s %s%s%s%n",
                    AnsiTheme.CMD_NAME, c[0], AnsiTheme.RESET,
                    AnsiTheme.MUTED, c[1], AnsiTheme.RESET));
        }
        ui.printStreamingChunk("\n");
    }

    private void waitForReady(Semaphore readyForInput) {
        try {
            readyForInput.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
