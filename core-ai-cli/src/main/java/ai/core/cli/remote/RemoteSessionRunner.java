package ai.core.cli.remote;

import ai.core.api.server.session.AgentSession;
import ai.core.cli.DebugLog;
import ai.core.cli.listener.RemoteEventListener;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;

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

    static String truncate(String text, int max) {
        if (text == null) return "";
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private final TerminalUI ui;
    private final AgentSession session;
    private final RemoteApiClient api;

    public RemoteSessionRunner(TerminalUI ui, AgentSession session, RemoteApiClient api) {
        this.ui = ui;
        this.session = session;
        this.api = api;
    }

    public void run() {
        var listener = new RemoteEventListener(ui, session);
        session.onEvent(listener);

        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);

        printBanner();
        startSenderThread(messageQueue, listener, readyForInput);
        readInputLoop(messageQueue, readyForInput);
        session.close();
    }

    private void printBanner() {
        ui.printStreamingChunk("\n" + AnsiTheme.WARNING + "  [REMOTE]" + AnsiTheme.RESET + " "
                + AnsiTheme.PROMPT + "core-ai" + AnsiTheme.RESET
                + AnsiTheme.MUTED + " → " + api.serverUrl() + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  session: " + session.id() + AnsiTheme.RESET + "\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  /help for commands, /exit to return to local mode" + AnsiTheme.RESET + "\n");
    }

    private void startSenderThread(BlockingQueue<String> queue, RemoteEventListener listener, Semaphore readyForInput) {
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
            var input = ui.readInput();
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
            case "/agents" -> handleAgents();
            case "/tools" -> handleTools();
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
                if (lower.startsWith("/agent ")) {
                    handleAgentDetail(cmd.substring(7).trim());
                    break;
                }
                if (lower.startsWith("/stats") || lower.startsWith("/model")
                        || lower.startsWith("/undo") || lower.startsWith("/compact") || lower.startsWith("/export")
                        || lower.startsWith("/copy") || lower.startsWith("/resume") || lower.startsWith("/memory")
                        || lower.startsWith("/skill") || lower.startsWith("/mcp")) {
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
                {"/agents", "List available agents"},
                {"/agent <id>", "Show agent details"},
                {"/tools", "List available tools"},
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
    private void handleAgents() {
        String json;
        try {
            json = api.get("/api/agents");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return;
        }
        if (json == null) {
            ui.showError("failed to fetch agents");
            return;
        }
        Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
        var agents = (List<Map<String, Object>>) response.get("agents");
        if (agents == null || agents.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No agents found.\n" + AnsiTheme.RESET);
            return;
        }
        ui.printStreamingChunk(String.format("%n  %sAgents (%d)%s%n", AnsiTheme.PROMPT, agents.size(), AnsiTheme.RESET));
        for (var agent : agents) {
            var id = (String) agent.get("id");
            var name = (String) agent.get("name");
            var isDefault = Boolean.TRUE.equals(agent.get("system_default"));
            var status = (String) agent.get("status");
            String marker = isDefault ? AnsiTheme.SUCCESS + " (default)" + AnsiTheme.RESET : "";
            String statusTag = status != null ? AnsiTheme.MUTED + " [" + status + "]" + AnsiTheme.RESET : "";
            ui.printStreamingChunk("  " + AnsiTheme.CMD_NAME + id + AnsiTheme.RESET + marker + statusTag + "\n");
            if (name != null) {
                ui.printStreamingChunk(AnsiTheme.MUTED + "    " + name);
                var desc = (String) agent.get("description");
                if (desc != null && !desc.isBlank()) {
                    ui.printStreamingChunk(" - " + truncate(desc, 60));
                }
                ui.printStreamingChunk(AnsiTheme.RESET + "\n");
            }
        }
        ui.printStreamingChunk("\n");
    }

    @SuppressWarnings("unchecked")
    private void handleAgentDetail(String agentId) {
        String json;
        try {
            json = api.get("/api/agents/" + agentId);
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return;
        }
        if (json == null) {
            ui.showError("failed to fetch agent: " + agentId);
            return;
        }
        Map<String, Object> agent = JsonUtil.fromJson(Map.class, json);
        ui.printStreamingChunk(String.format("%n  %sAgent: %s%s%n", AnsiTheme.PROMPT, agent.get("name"), AnsiTheme.RESET));
        printField("ID", agent.get("id"));
        printField("Model", agent.get("model"));
        printField("Temperature", agent.get("temperature"));
        printField("Max Turns", agent.get("max_turns"));
        printField("Status", agent.get("status"));
        var toolIds = (List<String>) agent.get("tool_ids");
        if (toolIds != null && !toolIds.isEmpty()) {
            printField("Tools", String.join(", ", toolIds));
        }
        var prompt = (String) agent.get("system_prompt");
        if (prompt != null && !prompt.isBlank()) {
            printField("System Prompt", truncate(prompt, 80));
        }
        ui.printStreamingChunk("\n");
    }

    @SuppressWarnings("unchecked")
    private void handleTools() {
        String json;
        try {
            json = api.get("/api/tools");
        } catch (RemoteApiException e) {
            ui.showError(e.getMessage());
            return;
        }
        if (json == null) {
            ui.showError("failed to fetch tools");
            return;
        }
        Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
        var tools = (List<Map<String, Object>>) response.get("tools");
        if (tools == null || tools.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No tools found.\n" + AnsiTheme.RESET);
            return;
        }
        ui.printStreamingChunk(String.format("%n  %sTools (%d)%s%n", AnsiTheme.PROMPT, tools.size(), AnsiTheme.RESET));
        for (var tool : tools) {
            var name = (String) tool.get("name");
            var desc = (String) tool.get("description");
            var type = (String) tool.get("type");
            var category = (String) tool.get("category");
            String typeTag = type != null ? AnsiTheme.MUTED + " [" + type + "]" + AnsiTheme.RESET : "";
            String catTag = category != null ? AnsiTheme.MUTED + " (" + category + ")" + AnsiTheme.RESET : "";
            ui.printStreamingChunk("  " + AnsiTheme.CMD_NAME + name + AnsiTheme.RESET + typeTag + catTag);
            if (desc != null && !desc.isBlank()) {
                ui.printStreamingChunk(AnsiTheme.MUTED + " - " + truncate(desc, 50) + AnsiTheme.RESET);
            }
            ui.printStreamingChunk("\n");
        }
        ui.printStreamingChunk("\n");
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
