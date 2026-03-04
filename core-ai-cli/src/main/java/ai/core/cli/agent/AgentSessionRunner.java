package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.DebugLog;
import ai.core.cli.command.McpCommandHandler;
import ai.core.cli.command.MemoryCommandHandler;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.command.SkillCommandHandler;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.BannerPrinter;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.config.ProviderConfigurator;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.RoleType;
import ai.core.memory.MemoryProvider;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionManager;
import ai.core.session.ToolPermissionStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class AgentSessionRunner {

    private static final String POISON_PILL = "\0__EXIT__";
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TerminalUI ui;
    private final Agent agent;
    private final LLMProviders llmProviders;
    private final String modelName;
    private final boolean autoApproveAll;
    private final String sessionId;
    private final SessionManager sessionManager;
    private final ToolPermissionStore permissionStore;
    private final ModelRegistry modelRegistry;
    private final MemoryCommandHandler memoryCommand;
    private final AtomicReference<String> switchSessionId = new AtomicReference<>();

    public AgentSessionRunner(TerminalUI ui, Agent agent, LLMProviders llmProviders, Config config) {
        this.ui = ui;
        this.agent = agent;
        this.llmProviders = llmProviders;
        this.modelName = config.modelName;
        this.autoApproveAll = config.autoApproveAll;
        this.sessionId = config.sessionId;
        this.sessionManager = config.sessionManager;
        this.permissionStore = config.permissionStore;
        this.modelRegistry = config.modelRegistry;
        this.memoryCommand = new MemoryCommandHandler(ui, config.memory);
    }

    public String run() {
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
        var listener = new CliEventListener(ui, session, agent);
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
        DebugLog.log("terminal: type=" + ui.getTerminalType()
                + ", jline=" + ui.isJLineEnabled() + ", ansi=" + ui.isAnsiSupported());
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
                dispatchCommand(trimmed, commands, queue);
                showFrame = true;
                if (switchSessionId.get() != null) break;
                readyForInput.release();
                continue;
            }
            showFrame = true;
            queue.offer(input);
        }
    }

    private void dispatchCommand(String trimmed, ReplCommandHandler commands, BlockingQueue<String> queue) {
        var lower = trimmed.toLowerCase(Locale.ROOT);
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
        } else if (lower.startsWith("/memory")) {
            memoryCommand.handle(trimmed);
        } else if ("/skill".equals(lower) || "/skills".equals(lower)) {
            new SkillCommandHandler(ui).handle();
        } else if ("/mcp".equals(lower)) {
            new McpCommandHandler(ui).handle();
        } else if ("/resume".equals(lower)) {
            String picked = showSessionPicker();
            if (picked != null) {
                switchSessionId.set(picked);
                queue.offer(POISON_PILL);
            }
        } else {
            commands.handle(trimmed);
        }
    }

    private void handleModelCommand(String trimmed) {
        String currentModel = getCurrentModelName();
        String[] parts = trimmed.split("\\s+", 2);
        if (parts.length >= 2) {
            switchModel(currentModel, parts[1].trim());
            return;
        }
        showModelPicker(currentModel);
    }

    private void showModelPicker(String currentModel) {
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Current model: " + AnsiTheme.RESET + currentModel + "\n\n");
        var models = buildModelList(currentModel);
        for (int i = 0; i < models.size(); i++) {
            var entry = models.get(i);
            String marker = entry.equals(currentModel) ? AnsiTheme.SUCCESS + " (active)" + AnsiTheme.RESET : "";
            ui.printStreamingChunk(String.format("  %s%2d)%s %s%s%n",
                    AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, entry, marker));
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.CMD_NAME + " a)" + AnsiTheme.RESET + " Add model to provider\n");
        ui.printStreamingChunk("  " + AnsiTheme.CMD_NAME + " b)" + AnsiTheme.RESET + " Configure new provider\n\n");
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Select (1-" + models.size() + "), a/b, model name, or 'q' to cancel: " + AnsiTheme.RESET);
        var line = ui.readRawLine();
        if (line == null || "q".equalsIgnoreCase(line.trim())) return;
        String input = line.trim();
        if ("a".equalsIgnoreCase(input)) {
            addModelToProvider();
            return;
        }
        if ("b".equalsIgnoreCase(input)) {
            configureProvider();
            return;
        }
        String choice = resolveModelChoice(input, models);
        if (choice != null) {
            switchModel(currentModel, choice);
        }
    }

    private List<String> buildModelList(String currentModel) {
        var models = new java.util.ArrayList<>(modelRegistry.getAllModels());
        if (!models.contains(currentModel)) {
            models.addFirst(currentModel);
        }
        return models;
    }

    private String resolveModelChoice(String input, List<String> models) {
        try {
            int idx = Integer.parseInt(input);
            if (idx >= 1 && idx <= models.size()) {
                return models.get(idx - 1);
            }
        } catch (NumberFormatException ignored) {
            // treat as model name
        }
        if (!input.isBlank()) return input;
        return null;
    }

    private void switchModel(String currentModel, String newModel) {
        var providerType = modelRegistry.getProviderType(newModel);
        if (providerType != null) {
            agent.setLlmProvider(llmProviders.getProvider(providerType));
            new ProviderConfigurator(ui, llmProviders, modelRegistry).saveActiveModel(providerType, newModel);
        } else {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET
                    + " Model not in registry, using current provider.\n");
        }
        agent.setModel(newModel);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                + " Model switched: " + currentModel + " → "
                + AnsiTheme.PROMPT + newModel + AnsiTheme.RESET + "\n\n");
    }

    private String getCurrentModelName() {
        return agent.getModel() != null
                ? agent.getModel()
                : agent.getLLMProvider().config.getModel();
    }

    private void handleStats() {
        var usage = agent.getCurrentTokenUsage();
        String currentModel = getCurrentModelName();
        int turns = (int) agent.getMessages().stream()
                .filter(m -> m.role == RoleType.USER).count();
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
        try (var writer = Files.newBufferedWriter(Path.of(filePath))) {
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
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Export failed: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void addModelToProvider() {
        var configurator = new ProviderConfigurator(ui, llmProviders, modelRegistry);
        configurator.addModelToProvider();
    }

    private void configureProvider() {
        var configurator = new ProviderConfigurator(ui, llmProviders, modelRegistry);
        var result = configurator.configure();
        if (result != null) {
            agent.setLlmProvider(llmProviders.getProvider(result.type()));
            agent.setModel(result.model());
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
            if (msg.role == RoleType.ASSISTANT && msg.getTextContent() != null) {
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
        int total = messages.size();
        if (total <= 4) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Nothing to compact.\n" + AnsiTheme.RESET);
            return;
        }
        // keep system prompt (index 0) + last 4 messages (2 user-assistant pairs)
        int removeEnd = total - 4;
        int removed = removeEnd - 1;
        messages.subList(1, removeEnd).clear();
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                + " Compacted: removed " + removed + " messages, kept " + messages.size() + "\n\n");
    }

    private String showSessionPicker() {
        var sessions = sessionManager.listSessions();
        if (sessions.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "No saved sessions found." + AnsiTheme.RESET + "\n");
            return null;
        }
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Recent sessions:" + AnsiTheme.RESET + "\n\n");
        int limit = Math.min(sessions.size(), 10);
        for (int i = 0; i < limit; i++) {
            var session = sessions.get(i);
            var marker = session.id().equals(sessionId) ? " (current)" : "";
            String timeStr = LocalDateTime.ofInstant(session.lastModified(), ZoneId.systemDefault()).format(DISPLAY_FORMAT);
            String title = sessionManager.firstUserMessage(session.id());
            String display = title != null && !title.isBlank()
                ? (title.length() > 50 ? title.substring(0, 50) + "..." : title).replaceAll("[\\r\\n]+", " ")
                : session.id();
            ui.printStreamingChunk(String.format("  %s%2d)%s %s %s(%s)%s%s%n",
                AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET,
                display,
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
                    var picked = sessions.get(choice - 1).id();
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

    private void waitForReady(Semaphore readyForInput) {
        try {
            readyForInput.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Config(String modelName, boolean autoApproveAll, String sessionId,
                         SessionManager sessionManager, ToolPermissionStore permissionStore,
                         MemoryProvider memory, ModelRegistry modelRegistry) {
    }
}
