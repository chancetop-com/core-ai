package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.command.MemoryCommandHandler;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.remote.RemoteConfig;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.OutputPanel;
import ai.core.cli.ui.BannerPrinter;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.StreamingMarkdownRenderer;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.ui.TextUtil;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.config.ProviderConfigurator;
import ai.core.llm.LLMProviderType;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.RoleType;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.SessionMemoryExtractor;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

public class AgentSessionRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSessionRunner.class);

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
    private final boolean memoryEnabled;
    private final SessionPersistence sessionPersistence;
    private final AtomicReference<String> switchSessionId = new AtomicReference<>();
    private final AtomicReference<RemoteConfig> remoteConfig = new AtomicReference<>();
    private ReplCommandHandler commands;

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
        this.memoryCommand = config.memoryEnabled ? new MemoryCommandHandler(ui, config.memory) : null;
        this.memoryEnabled = config.memoryEnabled;
        this.sessionPersistence = config.sessionPersistence;
    }

    public String run() {
        // Load session history if resuming an existing session
        if (agent.hasPersistenceProvider()) {
            agent.load(sessionId);
        }
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
        var listener = new CliEventListener(ui, session, agent);
        session.onEvent(listener);
        setupCompressionListener(listener);

        commands = new ReplCommandHandler(ui);
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);

        printBanner();
        extractPreviousSession();
        printSessionHistory();
        startSenderThread(messageQueue, listener, session, readyForInput);
        readInputLoop(messageQueue, readyForInput);

        session.close();
        return switchSessionId.get();
    }

    public void runPrompt(String prompt) {
        // Load session history if resuming an existing session
        if (agent.hasPersistenceProvider()) {
            agent.load(sessionId);
        }
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
        var listener = new CliEventListener(ui, session, agent);
        session.onEvent(listener);
        setupCompressionListener(listener);

        printBanner();
        extractPreviousSession();
        printSessionHistory();
        if (prompt != null && !prompt.isBlank()) {
            ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET + prompt.strip() + "\n");
        }
        sendPrompt(listener, session, prompt);

        session.close();
    }

    private void extractPreviousSession() {
        if (!memoryEnabled || memoryCommand == null) return;
        var extractor = new SessionMemoryExtractor(memoryCommand.getMemoryProvider(), agent.getLLMProvider(), agent.getModel(), sessionPersistence);
        if (!extractor.hasPendingSessions(sessionId)) return;
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Extracting memories from last session..." + AnsiTheme.RESET + "\n");
        extractor.extractPreviousSessionAsync(sessionId, () -> extractor.reloadAgentMemorySection(agent));
    }
    public RemoteConfig getRemoteConfig() {
        return remoteConfig.get();
    }
    private void printBanner() {
        BannerPrinter.print(ui.getWriter(), modelName);
        LOGGER.debug("terminal: type={}, jline={}, ansi={}", ui.getTerminalType(), ui.isJLineEnabled(), ui.isAnsiSupported());
    }
    private void printSessionHistory() {
        var messages = agent.getMessages();
        boolean hasHistory = false;
        var renderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported(), ui::getTerminalWidth);
        for (var msg : messages) {
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) continue;
            if (msg.role == RoleType.USER) {
                hasHistory = true;
                ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET + text.strip() + "\n");
            } else if (msg.role == RoleType.ASSISTANT) {
                ui.printStreamingChunk("\n" + AnsiTheme.SEPARATOR + "⏺" + AnsiTheme.RESET + "\n");
                renderer.processChunk(text);
                renderer.flush();
                renderer.reset();
                ui.getWriter().println();
            }
        }
        if (hasHistory) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  ↑ restored " + sessionId + AnsiTheme.RESET + "\n");
        }
    }
    private void startSenderThread(BlockingQueue<String> queue, CliEventListener listener, InProcessAgentSession session, Semaphore readyForInput) {
        Thread senderThread = new Thread(() -> {
            try {
                while (true) {
                    String msg = queue.take();
                    if (POISON_PILL.equals(msg)) break;
                    LOGGER.debug("sending message: {}", msg);
                    listener.prepareTurn();
                    session.sendMessage(msg);
                    LOGGER.debug("waiting for turn...");
                    listener.waitForTurn();
                    LOGGER.debug("turn finished");
                    readyForInput.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sender-thread");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    private void sendPrompt(CliEventListener listener, InProcessAgentSession session, String prompt) {
        LOGGER.debug("sending prompt: {}", prompt);
        listener.prepareTurn();
        String expanded = ui.getPasteBuffer().expand(prompt == null ? "" : prompt);
        session.sendMessage(FileReferenceExpander.expand(expanded));
        LOGGER.debug("waiting for turn...");
        listener.waitForTurn();
        LOGGER.debug("turn finished");
    }

    private void readInputLoop(BlockingQueue<String> queue, Semaphore readyForInput) {
        var dispatcher = new CommandDispatcher(ui, this, switchSessionId, remoteConfig, commands, memoryCommand, memoryEnabled);
        boolean showFrame = true;
        while (true) {
            try {
                readyForInput.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
                dispatcher.dispatch(trimmed, queue);
                showFrame = true;
                if (switchSessionId.get() != null || remoteConfig.get() != null) break;
                readyForInput.release();
                continue;
            }
            showFrame = true;
            String expanded = ui.getPasteBuffer().expand(input);
            queue.offer(FileReferenceExpander.expand(expanded));
        }
    }

    void showModelPicker() {
        String currentModel = getCurrentModelName();
        var currentProviderType = llmProviders.getProviderType(agent.getLLMProvider());
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + "Current model: " + AnsiTheme.RESET + currentModel + "\n\n");
        var entries = new java.util.ArrayList<>(modelRegistry.getAllEntries());
        if (entries.stream().noneMatch(e -> e.model().equals(currentModel))) {
            entries.addFirst(new ModelRegistry.ModelEntry(currentModel, modelRegistry.getProviderType(currentModel)));
        }
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String providerTag = AnsiTheme.MUTED + " [" + entry.providerType().getName() + "]" + AnsiTheme.RESET;
            boolean isActive = entry.model().equals(currentModel) && entry.providerType() == currentProviderType;
            String marker = isActive ? AnsiTheme.SUCCESS + " (active)" + AnsiTheme.RESET : "";
            ui.printStreamingChunk(String.format("  %s%2d)%s %s%s%s%n",
                    AnsiTheme.PROMPT, i + 1, AnsiTheme.RESET, entry.model(), providerTag, marker));
        }
        ui.printStreamingChunk(String.format("%n  %s a)%s Add model  %s b)%s New provider  %s c)%s Remove model%n%n",
                AnsiTheme.CMD_NAME, AnsiTheme.RESET, AnsiTheme.CMD_NAME, AnsiTheme.RESET, AnsiTheme.CMD_NAME, AnsiTheme.RESET));
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Select (1-" + entries.size() + "), a/b/c, model name, or 'q' to cancel: " + AnsiTheme.RESET);
        var line = ui.readRawLine();
        if (line == null || "q".equalsIgnoreCase(line.trim())) return;
        String input = line.trim();
        var configurator = new ProviderConfigurator(ui, llmProviders, modelRegistry);
        if ("a".equalsIgnoreCase(input)) {
            configurator.addModelToProvider();
            return;
        } else if ("b".equalsIgnoreCase(input)) {
            var result = configurator.configure();
            if (result != null) {
                agent.setLlmProvider(llmProviders.getProvider(result.type()));
                agent.setModel(result.model());
            }
            return;
        } else if ("c".equalsIgnoreCase(input)) {
            configurator.removeModelFromProvider();
            return;
        }
        try {
            int idx = Integer.parseInt(input);
            if (idx >= 1 && idx <= entries.size()) {
                var picked = entries.get(idx - 1);
                switchModel(currentModel, picked.model(), picked.providerType());
                return;
            }
        } catch (NumberFormatException ignored) {
            // treat as model name
        }
        if (!input.isBlank() && modelRegistry.getProviderType(input) != null) {
            switchModel(currentModel, input, null);
        }
    }

    void switchModel(String currentModel, String newModel, LLMProviderType providerType) {
        var currentProviderType = llmProviders.getProviderType(agent.getLLMProvider());
        if (currentModel.equals(newModel) && (providerType == null || providerType == currentProviderType)) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Already using " + newModel + AnsiTheme.RESET + "\n\n");
            return;
        }
        var resolvedType = providerType != null ? providerType : modelRegistry.getProviderType(newModel);
        if (resolvedType != null) {
            agent.setLlmProvider(llmProviders.getProvider(resolvedType));
            new ProviderConfigurator(ui, llmProviders, modelRegistry).saveActiveModel(resolvedType, newModel);
        } else {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET + " Model not in registry, using current provider.\n");
        }
        agent.setModel(newModel);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Model switched: "
                + currentModel + " → " + AnsiTheme.PROMPT + newModel + AnsiTheme.RESET + "\n\n");
    }
    String getCurrentModelName() {
        return agent.getModel() != null ? agent.getModel() : agent.getLLMProvider().config.getModel();
    }

    void handleStats() {
        var u = agent.getCurrentTokenUsage();
        String model = getCurrentModelName();
        int turns = (int) agent.getMessages().stream().filter(m -> m.role == RoleType.USER).count();
        ui.printStreamingChunk(String.format("%n  %sSession Stats%s%n  Model:       %s%n  Session:     %s%n  Turns:       %d%n  Tokens:      %,d (prompt: %,d, completion: %,d)%n  Tools:       %d available%n%n",
                AnsiTheme.PROMPT, AnsiTheme.RESET, model, sessionId, turns, (long) u.getTotalTokens(), (long) u.getPromptTokens(), (long) u.getCompletionTokens(), agent.getToolCalls().size()));
    }

    void handleTools() {
        var tools = agent.getToolCalls();
        ui.printStreamingChunk(String.format("%n  %sAvailable Tools (%d)%s%n", AnsiTheme.PROMPT, tools.size(), AnsiTheme.RESET));
        for (var tool : tools) {
            var d = tool.getDescription();
            var hint = (d == null || d.isBlank()) ? "" : AnsiTheme.MUTED + " - " + d.lines().findFirst().map(l -> l.length() > 60 ? l.substring(0, 57) + "..." : l).orElse("") + AnsiTheme.RESET;
            ui.printStreamingChunk("  " + AnsiTheme.CMD_NAME + tool.getName() + AnsiTheme.RESET + hint + "\n");
        }
        ui.printStreamingChunk("\n");
    }

    void handleExport(String trimmed) {
        String[] parts = trimmed.split("\\s+", 2);
        String filePath = parts.length > 1 ? parts[1].trim() : "session-" + sessionId + ".md";
        var sb = new StringBuilder(4096);
        sb.append("# Session: ").append(sessionId).append("\n\n");
        for (var msg : agent.getMessages()) {
            String text = msg.getTextContent();
            if (text != null) sb.append("## ").append(msg.role.name()).append("\n\n").append(text).append("\n\n");
        }
        try {
            Files.writeString(Path.of(filePath), sb.toString());
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Exported to " + filePath + "\n\n");
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Export failed: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }
    void handleCopy() {
        var messages = agent.getMessages();
        String lastAssistant = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role == RoleType.ASSISTANT && messages.get(i).getTextContent() != null) {
                lastAssistant = messages.get(i).getTextContent();
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
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Copied to clipboard (" + lastAssistant.length() + " chars)\n\n");
        } catch (Exception e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to copy: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private void setupCompressionListener(CliEventListener listener) {
        var compression = agent.getCompression();
        if (compression == null) return;
        OutputPanel panel = listener.getPanel();
        compression.setListener((beforeCount, afterCount, completed) -> {
            panel.stopSpinnerIfActive();
            String msg = completed
                    ? "\n  " + AnsiTheme.SUCCESS + "\u2726" + AnsiTheme.RESET + AnsiTheme.MUTED + " Compressed: " + beforeCount + " \u2192 " + afterCount + " messages" + AnsiTheme.RESET + "\n"
                    : "\n  " + AnsiTheme.MUTED + "\u2726 Compressing " + afterCount + " messages..." + AnsiTheme.RESET;
            ui.printStreamingChunk(msg);
            panel.startSpinner();
        });
    }

    void handleCompact() {
        var messages = agent.getMessages();
        var compression = agent.getCompression();
        if (messages.size() <= 4 || compression == null) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Nothing to compact.\n" + AnsiTheme.RESET);
            return;
        }
        int beforeCount = messages.size();
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Compacting...\n" + AnsiTheme.RESET);
        var compressed = compression.forceCompress(messages);
        if (compressed.equals(messages)) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Nothing to compact.\n" + AnsiTheme.RESET);
            return;
        }
        messages.clear();
        messages.addAll(compressed);
        if (agent.hasPersistenceProvider()) agent.save(sessionId);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Compacted: "
                + beforeCount + " → " + messages.size() + " messages\n\n");
    }
    void handleUndo() {
        var messages = agent.getMessages();
        int idx = messages.size() - 1;
        while (idx >= 0 && messages.get(idx).role != RoleType.USER) idx--;
        if (idx < 0) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Nothing to undo.\n" + AnsiTheme.RESET);
            return;
        }
        String preview = messages.get(idx).getTextContent();
        if (preview != null && preview.length() > 60) preview = preview.substring(0, 57) + "...";
        int removed = messages.size() - idx;
        messages.subList(idx, messages.size()).clear();
        if (agent.hasPersistenceProvider()) agent.save(sessionId);
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET + " Undone " + removed
                + " message(s): " + AnsiTheme.MUTED + preview + AnsiTheme.RESET + "\n\n");
    }

    String showSessionPicker() {
        var sessions = sessionManager.listSessions();
        if (sessions.isEmpty()) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "No saved sessions found." + AnsiTheme.RESET + "\n");
            return null;
        }
        int limit = Math.min(sessions.size(), 10);
        List<String> labels = new java.util.ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            var s = sessions.get(i);
            String marker = s.id().equals(sessionId) ? " (current)" : "";
            String timeStr = LocalDateTime.ofInstant(s.lastModified(), ZoneId.systemDefault()).format(DISPLAY_FORMAT);
            String title = sessionManager.firstUserMessage(s.id());
            String display = title != null && !title.isBlank() ? TextUtil.truncateByDisplayWidth(title.replaceAll("[\\r\\n]+", " "), 50) : s.id();
            labels.add(display + " (" + timeStr + ")" + marker);
        }
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Recent sessions:" + AnsiTheme.RESET + AnsiTheme.MUTED + " (↑↓ select, Enter confirm, q/Esc cancel)" + AnsiTheme.RESET + "\n");
        int choice = ui.pickIndex(labels);
        if (choice < 0) return null;
        var picked = sessions.get(choice).id();
        if (picked.equals(sessionId)) {
            ui.printStreamingChunk(AnsiTheme.MUTED + "Already in this session." + AnsiTheme.RESET + "\n");
            return null;
        }
        ui.printStreamingChunk(AnsiTheme.MUTED + "Switching to session: " + picked + AnsiTheme.RESET + "\n");
        return picked;
    }

    public record Config(String modelName, boolean autoApproveAll, String sessionId,
                         SessionManager sessionManager, ToolPermissionStore permissionStore,
                         MdMemoryProvider memory, ModelRegistry modelRegistry,
                         SessionPersistence sessionPersistence,
                         boolean memoryEnabled) {

        public Config(String modelName, boolean autoApproveAll, String sessionId,
                      SessionManager sessionManager, ToolPermissionStore permissionStore,
                      MdMemoryProvider memory, ModelRegistry modelRegistry,
                      SessionPersistence sessionPersistence) {
            this(modelName, autoApproveAll, sessionId, sessionManager, permissionStore, memory, modelRegistry, sessionPersistence, true);
        }
    }
}
