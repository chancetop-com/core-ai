package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.DebugLog;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.BannerPrinter;
import ai.core.cli.ui.TerminalUI;
import ai.core.persistence.providers.FilePersistenceProvider;
import ai.core.session.InProcessAgentSession;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class AgentSessionRunner {

    private static final String POISON_PILL = "\0__EXIT__";

    private final TerminalUI ui;
    private final Agent agent;
    private final String modelName;
    private final boolean autoApproveAll;
    private final String sessionId;
    private final FilePersistenceProvider persistenceProvider;
    private final AtomicReference<String> switchSessionId = new AtomicReference<>();

    public AgentSessionRunner(TerminalUI ui, Agent agent, String modelName,
                              boolean autoApproveAll, String sessionId,
                              FilePersistenceProvider persistenceProvider) {
        this.ui = ui;
        this.agent = agent;
        this.modelName = modelName;
        this.autoApproveAll = autoApproveAll;
        this.sessionId = sessionId;
        this.persistenceProvider = persistenceProvider;
    }

    public String run() {
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll);
        var listener = new CliEventListener(ui, session);
        session.onEvent(listener);

        var commands = new ReplCommandHandler(ui);
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);

        printBanner();
        startSenderThread(messageQueue, listener, session, readyForInput);
        readInputLoop(commands, messageQueue, readyForInput);

        session.close();
        return switchSessionId.get();
    }

    private void printBanner() {
        BannerPrinter.print(ui.getWriter(), ui.getTerminalWidth(), modelName);
        // TODO: temporary diagnostic, remove after confirming terminal type
        ui.getWriter().println("[diag] terminal: type=" + ui.getTerminalType()
                + ", jline=" + ui.isJLineEnabled() + ", ansi=" + ui.isAnsiSupported());
        ui.getWriter().flush();
    }

    private void startSenderThread(BlockingQueue<String> queue, CliEventListener listener,
                                   InProcessAgentSession session, Semaphore readyForInput) {
        Thread senderThread = new Thread(() -> {
            try {
                while (true) {
                    String msg = queue.take();
                    if (POISON_PILL.equals(msg)) {
                        break;
                    }
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

    private void readInputLoop(ReplCommandHandler commands, BlockingQueue<String> queue, Semaphore readyForInput) {
        boolean showFrame = true;
        while (true) {
            waitForReady(readyForInput);
            if (showFrame) {
                ui.printInputFrame();
            }
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
            if (trimmed.startsWith("/")) {
                showFrame = dispatchCommand(trimmed, commands, queue);
                if (showFrame && switchSessionId.get() != null) break;
                readyForInput.release();
                continue;
            }
            showFrame = true;
            queue.offer(input);
        }
    }

    private boolean dispatchCommand(String trimmed, ReplCommandHandler commands, BlockingQueue<String> queue) {
        var lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("/model")) {
            handleModelCommand(trimmed);
        } else if ("/stats".equals(lower)) {
            handleStats();
        } else if ("/tools".equals(lower)) {
            handleTools();
        } else if ("/copy".equals(lower)) {
            handleCopy();
        } else if ("/compact".equals(lower)) {
            handleCompact();
        } else if (lower.startsWith("/export")) {
            handleExport(trimmed);
        } else if ("/resume".equals(lower)) {
            String picked = showSessionPicker();
            if (picked != null) {
                switchSessionId.set(picked);
                queue.offer(POISON_PILL);
            }
        } else {
            commands.handle(trimmed);
        }
        return true;
    }

    private void handleModelCommand(String trimmed) {
        String currentModel = agent.getModel() != null
                ? agent.getModel()
                : agent.getLLMProvider().config.getModel();
        String[] parts = trimmed.split("\\s+", 2);
        if (parts.length < 2) {
            ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Current model: " + AnsiTheme.RESET + currentModel + "\n");
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Usage: /model <model-name>" + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Example: /model anthropic/claude-sonnet-4.6" + AnsiTheme.RESET + "\n\n");
            return;
        }
        String newModel = parts[1].trim();
        agent.setModel(newModel);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                + " Model switched: " + currentModel + " → "
                + AnsiTheme.PROMPT + newModel + AnsiTheme.RESET + "\n\n");
    }

    private void handleStats() {
        var usage = agent.getCurrentTokenUsage();
        String currentModel = agent.getModel() != null
                ? agent.getModel()
                : agent.getLLMProvider().config.getModel();
        int turns = (int) agent.getMessages().stream()
                .filter(m -> m.role == ai.core.llm.domain.RoleType.USER).count();
        String tokens = String.format("%,d (prompt: %,d, completion: %,d)",
                (long) usage.getTotalTokens(), (long) usage.getPromptTokens(), (long) usage.getCompletionTokens());
        ui.printStreamingChunk(String.format("%n  %sSession Stats%s%n  Model:       %s%n  Session:     %s%n  Turns:       %d%n  Tokens:      %s%n  Tools:       %d available%n%n",
                AnsiTheme.PROMPT, AnsiTheme.RESET, currentModel, sessionId, turns, tokens, agent.getToolCalls().size()));
    }

    private void handleTools() {
        var tools = agent.getToolCalls();
        ui.printStreamingChunk(String.format("%n  %sAvailable Tools (%d)%s%n", AnsiTheme.PROMPT, tools.size(), AnsiTheme.RESET));
        for (var tool : tools) {
            String desc = tool.getDescription();
            String summary = formatToolSummary(desc);
            ui.printStreamingChunk("  " + AnsiTheme.CMD_NAME + tool.getName() + AnsiTheme.RESET + summary + "\n");
        }
        ui.printStreamingChunk("\n");
    }

    private void handleExport(String trimmed) {
        String[] parts = trimmed.split("\\s+", 2);
        String filePath = parts.length > 1 ? parts[1].trim() : "session-" + sessionId + ".md";
        var messages = agent.getMessages();
        try (var writer = java.nio.file.Files.newBufferedWriter(java.nio.file.Path.of(filePath))) {
            writer.write("# Session: " + sessionId);
            writer.newLine();
            writer.newLine();
            for (var msg : messages) {
                String role = msg.role.name();
                String text = msg.getTextContent();
                if (text == null) continue;
                writer.write("## " + role);
                writer.newLine();
                writer.newLine();
                writer.write(text);
                writer.newLine();
                writer.newLine();
            }
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                    + " Exported to " + filePath + "\n\n");
        } catch (java.io.IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Export failed: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private String formatToolSummary(String desc) {
        if (desc == null || desc.isBlank()) {
            return "";
        }
        String firstLine = desc.lines().findFirst().orElse("").trim();
        if (firstLine.length() > 60) {
            firstLine = firstLine.substring(0, 57) + "...";
        }
        return AnsiTheme.MUTED + " - " + firstLine + AnsiTheme.RESET;
    }

    private void handleCopy() {
        var messages = agent.getMessages();
        String lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.role == ai.core.llm.domain.RoleType.ASSISTANT && msg.getTextContent() != null) {
                lastAssistant = msg.getTextContent();
                break;
            }
        }
        if (lastAssistant == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  No assistant response to copy.\n" + AnsiTheme.RESET);
            return;
        }
        try {
            var selection = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            selection.setContents(new java.awt.datatransfer.StringSelection(lastAssistant), null);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Copied to clipboard ("
                    + lastAssistant.length() + " chars)\n\n");
        } catch (Exception e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to copy: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void handleCompact() {
        var messages = agent.getMessages();
        int before = messages.size();
        if (before <= 2) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Nothing to compact.\n" + AnsiTheme.RESET);
            return;
        }
        // keep system prompt (first) + last 2 user-assistant pairs
        int keep = Math.min(4, before - 1);
        int removed = 0;
        while (messages.size() > keep + 1) {
            messages.remove(1);
            removed++;
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                + " Compacted: removed " + removed + " messages, kept " + messages.size() + "\n\n");
    }

    private String showSessionPicker() {
        List<String> sessions = persistenceProvider.listSessions();
        if (sessions.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "No saved sessions found." + AnsiTheme.RESET + "\n");
            return null;
        }
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Recent sessions:" + AnsiTheme.RESET + "\n\n");
        int limit = Math.min(sessions.size(), 10);
        for (int i = 0; i < limit; i++) {
            var id = sessions.get(i);
            var marker = id.equals(sessionId) ? " (current)" : "";
            var filePath = Paths.get(persistenceProvider.path(id));
            String timeStr = formatFileTime(filePath);
            ui.printStreamingChunk(String.format("  %s%2d)%s %s %s(%s)%s%s%n",
                AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET,
                id,
                AnsiTheme.MUTED, timeStr, marker, AnsiTheme.RESET));
        }
        ui.printStreamingChunk("\n");

        while (true) {
            ui.printStreamingChunk(AnsiTheme.PROMPT + "Select session (1-" + limit + "), or 'q' to cancel: " + AnsiTheme.RESET);
            var line = ui.readRawLine();
            if (line == null || "q".equalsIgnoreCase(line.trim())) {
                return null;
            }
            try {
                int choice = Integer.parseInt(line.trim());
                if (choice >= 1 && choice <= limit) {
                    var picked = sessions.get(choice - 1);
                    if (picked.equals(sessionId)) {
                        ui.printStreamingChunk(AnsiTheme.MUTED + "Already in this session." + AnsiTheme.RESET + "\n");
                        return null;
                    }
                    ui.printStreamingChunk(AnsiTheme.MUTED + "Switching to session: " + picked + AnsiTheme.RESET + "\n");
                    return picked;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
            ui.printStreamingChunk(AnsiTheme.WARNING + "Invalid selection." + AnsiTheme.RESET + "\n");
        }
    }

    private String formatFileTime(java.nio.file.Path path) {
        try {
            var modified = java.nio.file.Files.getLastModifiedTime(path).toInstant();
            var local = java.time.LocalDateTime.ofInstant(modified, java.time.ZoneId.systemDefault());
            return local.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return "unknown";
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
