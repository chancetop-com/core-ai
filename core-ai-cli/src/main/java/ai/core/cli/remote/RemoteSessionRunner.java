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
        new SlashCommand("/tools", "List available tools"),
        new SlashCommand("/load-tool", "Load additional tools for this session"),
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
            case "/load-tool" -> handleLoadTool();
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
                {"/tools", "List available tools"},
                {"/load-tool", "Load additional tools for this session"},
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

    private void printToolLine(Map<String, Object> tool, String prefix, int descMaxLen) {
        var name = (String) tool.get("name");
        var desc = (String) tool.get("description");
        var type = (String) tool.get("type");
        var category = (String) tool.get("category");
        String typeTag = type != null ? AnsiTheme.MUTED + " [" + type + "]" + AnsiTheme.RESET : "";
        String catTag = category != null ? AnsiTheme.MUTED + " (" + category + ")" + AnsiTheme.RESET : "";
        String descTag = desc != null && !desc.isBlank() ? AnsiTheme.MUTED + " - " + truncate(desc, descMaxLen) + AnsiTheme.RESET : "";
        ui.printStreamingChunk(prefix + AnsiTheme.CMD_NAME + name + AnsiTheme.RESET + typeTag + catTag + descTag + "\n");
    }

    private void handleTools() {
        var tools = fetchTools();
        if (tools == null) return;
        ui.printStreamingChunk(String.format("%n  %sTools (%d)%s%n", AnsiTheme.PROMPT, tools.size(), AnsiTheme.RESET));
        for (var tool : tools) {
            printToolLine(tool, "  ", 50);
        }
        ui.printStreamingChunk("\n");
    }

    private void handleLoadTool() {
        var tools = fetchTools();
        if (tools == null) return;

        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "  Select tools to load:" + AnsiTheme.RESET + "\n\n");
        for (int i = 0; i < tools.size(); i++) {
            printToolLine(tools.get(i), String.format("  %s%2d.%s ", AnsiTheme.CMD_NAME, i + 1, AnsiTheme.RESET), 40);
        }

        var selection = ui.readRawLine("\n  " + AnsiTheme.MUTED + "(e.g., 1,3,5 or 1-3)" + AnsiTheme.RESET + "\n  Selection: ");
        if (selection == null || selection.isBlank()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Cancelled.\n" + AnsiTheme.RESET);
            return;
        }

        var toolIds = parseSelection(selection, tools);
        if (toolIds.isEmpty()) {
            ui.showError("invalid selection");
            return;
        }

        try {
            var request = new LoadToolsRequest();
            request.toolIds = toolIds;
            var resultJson = api.post("/api/sessions/" + session.id() + "/tools", request);
            if (resultJson == null) {
                ui.showError("failed to load tools");
                return;
            }
            var result = JsonUtil.fromJson(LoadToolsResponse.class, resultJson);
            if (result.loadedTools != null && !result.loadedTools.isEmpty()) {
                ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Loaded " + result.loadedTools.size() + " tool(s):" + AnsiTheme.RESET + "\n");
                for (var toolName : result.loadedTools) {
                    ui.printStreamingChunk("  - " + AnsiTheme.CMD_NAME + toolName + AnsiTheme.RESET + "\n");
                }
            } else {
                ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "No tools were loaded." + AnsiTheme.RESET + "\n");
            }
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
        }
    }

    private List<String> parseSelection(String selection, List<Map<String, Object>> tools) {
        var toolIds = new ArrayList<String>();
        for (var part : selection.split(",")) {
            var p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("-")) {
                var range = p.split("-", 2);
                try {
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    for (int i = Math.max(start, 1); i <= Math.min(end, tools.size()); i++) {
                        addToolId(toolIds, tools.get(i - 1));
                    }
                } catch (NumberFormatException e) {
                    ui.printStreamingChunk(AnsiTheme.WARNING + "  Skipping invalid range: " + p + AnsiTheme.RESET + "\n");
                }
            } else {
                try {
                    int idx = Integer.parseInt(p);
                    if (idx >= 1 && idx <= tools.size()) {
                        addToolId(toolIds, tools.get(idx - 1));
                    }
                } catch (NumberFormatException e) {
                    ui.printStreamingChunk(AnsiTheme.WARNING + "  Skipping invalid input: " + p + AnsiTheme.RESET + "\n");
                }
            }
        }
        return toolIds;
    }

    private void addToolId(List<String> toolIds, Map<String, Object> tool) {
        var id = (String) tool.get("id");
        if (id != null && !toolIds.contains(id)) {
            toolIds.add(id);
        }
    }

    private void waitForReady(Semaphore readyForInput) {
        try {
            readyForInput.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
