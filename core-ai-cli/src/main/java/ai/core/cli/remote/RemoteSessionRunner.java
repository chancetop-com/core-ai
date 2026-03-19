package ai.core.cli.remote;

import ai.core.api.server.session.LoadToolsRequest;
import ai.core.api.server.session.LoadToolsResponse;
import ai.core.cli.DebugLog;
import ai.core.cli.command.SlashCommand;
import ai.core.cli.listener.RemoteEventListener;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        new SlashCommand("/mcp", "Manage MCP server connections"),
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
        if (name != null && !name.isBlank()) {
            return name + "@" + host;
        }
        return host;
    }

    private final TerminalUI ui;
    private final RemoteApiClient api;
    private final String promptPrefix;
    private volatile HttpAgentSession session;
    private volatile RemoteEventListener listener;
    private volatile String currentPrompt;

    public RemoteSessionRunner(TerminalUI ui, HttpAgentSession session, RemoteApiClient api, String name, String agentId) {
        this.ui = ui;
        this.api = api;
        this.promptPrefix = buildPromptPrefix(name, api.serverUrl());
        this.session = session;
        this.listener = new RemoteEventListener(ui, session);
        this.currentPrompt = promptPrefix + ":" + agentId + "> ";
    }

    public void run() {
        session.onEvent(listener);

        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);

        ui.setSlashCommands(REMOTE_COMMANDS);
        try {
            printBanner();
            startSenderThread(messageQueue, readyForInput);
            readInputLoop(messageQueue, readyForInput);
            session.close();
        } finally {
            ui.resetSlashCommands();
        }
    }

    private void switchAgent(String agentId, String agentName) {
        session.close();
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Switching to " + agentName + "..." + AnsiTheme.RESET + "\n");
        try {
            session = HttpAgentSession.connect(api, agentId);
            listener = new RemoteEventListener(ui, session);
            session.onEvent(listener);
            currentPrompt = promptPrefix + ":" + agentName + "> ";
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Session: " + session.id() + AnsiTheme.RESET + "\n\n");
        } catch (RuntimeException e) {
            ui.showError("failed to switch agent: " + e.getMessage());
        }
    }

    private void printBanner() {
        ui.printStreamingChunk("\n" + AnsiTheme.WARNING + "  [REMOTE]" + AnsiTheme.RESET + " "
                + AnsiTheme.PROMPT + "core-ai" + AnsiTheme.RESET
                + AnsiTheme.MUTED + " → " + api.serverUrl() + AnsiTheme.RESET + "\n");
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
            case "/build-agent" -> new CreateAgentCommandHandler(ui, api, session.id()).handle();
            case "/agents" -> {
                var switchReq = new AgentCommandHandler(ui, api).handle();
                if (switchReq != null) {
                    switchAgent(switchReq.agentId(), switchReq.agentName());
                }
            }
            case "/tools" -> handleTools();
            case "/mcp" -> new McpServerCommandHandler(ui, api).handle();
            case "/debug" -> {
                if (DebugLog.isEnabled()) {
                    DebugLog.disable();
                    ui.printStreamingChunk("Debug mode: OFF\n");
                } else {
                    DebugLog.enable();
                    System.setProperty("core.ai.debug", "true");
                    ui.printStreamingChunk("Debug mode: ON\n");
                }
            }
            case "/clear" -> ui.printStreamingChunk("\u001B[2J\u001B[H");
            default -> {
                if (lower.startsWith("/stats") || lower.startsWith("/model")
                        || lower.startsWith("/undo") || lower.startsWith("/compact") || lower.startsWith("/export")
                        || lower.startsWith("/copy") || lower.startsWith("/resume") || lower.startsWith("/memory")
                        || lower.startsWith("/skill")) {
                    ui.printStreamingChunk(AnsiTheme.MUTED + "  Not available in remote mode.\n" + AnsiTheme.RESET);
                    break;
                }
                ui.printStreamingChunk(AnsiTheme.WARNING + "Unknown command: " + cmd.split("\\s+", 2)[0] + ". Type /help for available commands." + AnsiTheme.RESET + "\n");
            }
        }
        return true;
    }

    private void printHelp() {
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Remote Commands:" + AnsiTheme.RESET + "\n");
        String[][] cmds = {
                {"/build-agent", "Build agent from current conversation, so it can be scheduled periodically on the server"},
                {"/agents", "Switch agent or manage agents"},
                {"/tools", "List and load available tools"},
                {"/mcp", "Manage MCP server connections (add, enable, disable, edit, delete)"},
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTools() {
        String json;
        try {
            json = api.get("/api/tools");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return null;
        }
        if (json == null) {
            ui.showError("failed to fetch tools");
            return null;
        }
        Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
        var tools = (List<Map<String, Object>>) response.get("tools");
        if (tools == null || tools.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No tools found.\n" + AnsiTheme.RESET);
            return null;
        }
        return tools;
    }

    private void handleTools() {
        var tools = fetchTools();
        if (tools == null) return;

        ui.printStreamingChunk(String.format("%n  %sTools (%d)%s%n", AnsiTheme.PROMPT, tools.size(), AnsiTheme.RESET));
        var labels = buildToolLabels(tools);
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Select tool:" + AnsiTheme.RESET + "\n");
        int selected = ui.pickIndex(labels);
        if (selected < 0) return;

        var tool = tools.get(selected);
        printToolDetail(tool);

        var actions = List.of("Load to session", "Back");
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Action:" + AnsiTheme.RESET + "\n");
        int actionIdx = ui.pickIndex(actions);
        if (actionIdx != 0) return;

        loadToolToSession((String) tool.get("id"), (String) tool.get("name"));
    }

    private List<String> buildToolLabels(List<Map<String, Object>> tools) {
        var labels = new ArrayList<String>();
        for (var tool : tools) {
            var name = (String) tool.get("name");
            var type = (String) tool.get("type");
            var category = (String) tool.get("category");
            var desc = (String) tool.get("description");
            var sb = new StringBuilder(name != null ? name : (String) tool.get("id"));
            if (type != null) sb.append(" [").append(type).append(']');
            if (category != null) sb.append(" (").append(category).append(')');
            if (desc != null && !desc.isBlank()) sb.append(" - ").append(truncate(desc, 40));
            labels.add(sb.toString());
        }
        return labels;
    }

    private void printToolDetail(Map<String, Object> tool) {
        ui.printStreamingChunk(String.format("%n  %s%s%s%n", AnsiTheme.PROMPT, tool.get("name"), AnsiTheme.RESET));
        printField("ID", tool.get("id"));
        printField("Type", tool.get("type"));
        printField("Category", tool.get("category"));
        var desc = (String) tool.get("description");
        if (desc != null && !desc.isBlank()) {
            printField("Description", desc);
        }
    }

    private void loadToolToSession(String toolId, String toolName) {
        if (toolId == null) {
            ui.showError("tool has no id");
            return;
        }
        try {
            var request = new LoadToolsRequest();
            request.toolIds = List.of(toolId);
            var resultJson = api.post("/api/sessions/" + session.id() + "/tools", request);
            if (resultJson == null) {
                ui.showError("failed to load tool");
                return;
            }
            var result = JsonUtil.fromJson(LoadToolsResponse.class, resultJson);
            if (result.loadedTools != null && !result.loadedTools.isEmpty()) {
                ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Loaded: " + String.join(", ", result.loadedTools) + AnsiTheme.RESET + "\n");
            } else {
                ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "Tool was not loaded." + AnsiTheme.RESET + "\n");
            }
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private void printField(String label, Object value) {
        if (value == null) return;
        ui.printStreamingChunk(String.format("  %s%-15s%s %s%n", AnsiTheme.MUTED, label + ":", AnsiTheme.RESET, value));
    }

    private void waitForReady(Semaphore readyForInput) {
        try {
            readyForInput.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
