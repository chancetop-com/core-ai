package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.command.HandlerContext;
import ai.core.cli.command.MemoryCommandHandler;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.hook.ScriptHookLifecycle;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemorySectionManager;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.memory.SessionCloseExtractor;
import ai.core.cli.remote.RemoteConfig;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.BannerPrinter;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.OutputPanel;
import ai.core.cli.ui.StreamingMarkdownRenderer;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.ui.TextUtil;
import ai.core.cli.upgrade.VersionUtil;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.RoleType;
import ai.core.utils.JsonUtil;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
public class AgentSessionRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSessionRunner.class);
    private static final String POISON_PILL = "\0__EXIT__";
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");
    private final TerminalUI ui;
    private final Agent agent;
    private final String modelName;
    private final boolean autoApproveAll;
    private final String sessionId;
    private final SessionManager sessionManager;
    private final ToolPermissionStore permissionStore;
    private final MemoryCommandHandler memoryCommand;
    private final boolean memoryEnabled;
    private final boolean dailyLogsEnabled;
    private final boolean promptExtractionEnabled;
    private final Integer timeLimitSeconds;
    private final Path workspace;
    private final AtomicReference<String> switchSessionId = new AtomicReference<>();
    private final AtomicReference<RemoteConfig> remoteConfig = new AtomicReference<>();
    private final SessionUpgradeHandler upgradeHandler;
    private final ModelPicker modelPicker;
    private ReplCommandHandler commands;
    private final String defaultServerUrl;
    public AgentSessionRunner(TerminalUI ui, Agent agent, LLMProviders llmProviders, Config config) {
        this.ui = ui;
        this.agent = agent;
        this.modelName = config.modelName;
        this.autoApproveAll = config.autoApproveAll;
        this.sessionId = config.sessionId;
        this.sessionManager = config.sessionManager;
        this.permissionStore = config.permissionStore;
        ModelRegistry modelRegistry = config.modelRegistry;
        this.workspace = Path.of((String) agent.getExecutionContext().getCustomVariables().get("workspace"));
        this.memoryCommand = config.memoryEnabled
                ? new MemoryCommandHandler(ui, config.memory, MemoryTriggerService.getInstance())
                : null;
        this.memoryEnabled = config.memoryEnabled;
        this.dailyLogsEnabled = config.dailyLogsEnabled;
        this.promptExtractionEnabled = config.promptExtractionEnabled;
        this.timeLimitSeconds = config.timeLimitSeconds;
        if (config.timeLimitSeconds != null && config.timeLimitSeconds > 0) {
            agent.getExecutionContext().getCustomVariables().put("time_limit_seconds", config.timeLimitSeconds);
        }
        this.upgradeHandler = new SessionUpgradeHandler(ui);
        this.modelPicker = new ModelPicker(ui, agent, llmProviders, modelRegistry);
        this.defaultServerUrl = config.defaultServerUrl;
    }
    public String run() {
        if (agent.hasPersistenceProvider()) {
            agent.load(sessionId);
            if (!agent.getMessages().isEmpty() && memoryEnabled) {
                MemoryTriggerService.getInstance().resetCursorToEnd();
            }
        }
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
        var listener = new CliEventListener(ui, session, agent);
        session.onEvent(listener);
        setupCompressionListener(listener);
        commands = new ReplCommandHandler(ui);
        BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
        Semaphore readyForInput = new Semaphore(1);
        printBanner();
        upgradeHandler.checkAndHintUpgrade();
        printSessionHistory();
        startSenderThread(messageQueue, listener, session, readyForInput);
        readInputLoop(messageQueue, readyForInput);
        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Organizing memories..." + AnsiTheme.RESET + "\n");
        SessionCloseExtractor.onSessionClose(agent, workspace, memoryEnabled, dailyLogsEnabled, switchSessionId);
        session.close();
        ScriptHookLifecycle.fireSessionStopHooks(workspace);
        return switchSessionId.get();
    }
    public void runPrompt(String prompt) {
        if (agent.hasPersistenceProvider()) {
            agent.load(sessionId);
        }
        var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore);
        var listener = new CliEventListener(ui, session, agent);
        session.onEvent(listener);
        setupCompressionListener(listener);
        printBanner();
        printSessionHistory();
        if (prompt != null && !prompt.isBlank()) {
            ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "❯  " + AnsiTheme.RESET + prompt.strip() + "\n");
        }
        if (timeLimitSeconds != null && timeLimitSeconds > 0) {
            LOGGER.info("Agent time limit: {}s", timeLimitSeconds);
            runPromptWithTimeLimit(listener, session, prompt, timeLimitSeconds);
        } else {
            sendPrompt(listener, session, prompt);
        }
        if (memoryEnabled && promptExtractionEnabled) {
            MemoryTriggerService.getInstance().runIncrementalExtractionAndWait();
        }
        session.close();
        ScriptHookLifecycle.fireSessionStopHooks(workspace);
    }

    private void runPromptWithTimeLimit(CliEventListener listener, InProcessAgentSession session,
                                         String prompt, int timeLimitSeconds) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "prompt-time-limit");
            t.setDaemon(true);
            return t;
        });
        Future<?> future = executor.submit(() -> {
            sendPrompt(listener, session, prompt);
            return null;
        });
        try {
            future.get(timeLimitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOGGER.info("Time limit ({}s) reached", timeLimitSeconds);
            future.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
        } catch (ExecutionException e) {
            LOGGER.error("Agent execution failed", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    public RemoteConfig getRemoteConfig() {
        return remoteConfig.get();
    }
    private void printBanner() {
        BannerPrinter.print(ui.getWriter(), modelName, VersionUtil.getCurrentVersion(), null);
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
        var dispatcher = new CommandDispatcher(
                ui, modelPicker, switchSessionId, remoteConfig,
                new HandlerContext(commands, memoryCommand, memoryEnabled), this, defaultServerUrl,
                agent.getExecutionContext().getAgentProfileRegistry());
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
            if ("/upgrade".equalsIgnoreCase(trimmed)) {
                showFrame = true;
                boolean exitForUpgrade = upgradeHandler.handleUpgrade();
                if (exitForUpgrade) {
                    queue.offer(POISON_PILL);
                    break;
                }
                readyForInput.release();
                continue;
            }
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
    void handleStats() {
        var u = agent.getCurrentTokenUsage();
        String model = modelPicker.getCurrentModelName();
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
    void handleThinking(String trimmed) {
        String[] parts = trimmed.split("\\s+", 2);
        if (parts.length > 1 && !parts[1].isBlank()) {
            var arg = parts[1].trim().toLowerCase(java.util.Locale.ROOT);
            var level = parseLevel(arg);
            if (level == null && !"off".equals(arg) && !"none".equals(arg) && !"default".equals(arg)) {
                ui.printStreamingChunk(AnsiTheme.ERROR + "  Invalid level: " + arg + ". Use low, medium, high, max, or off.\n" + AnsiTheme.RESET);
                return;
            }
            String error = persistReasoningEffortToExtraBody(level);
            if (error != null) {
                ui.printStreamingChunk(AnsiTheme.WARNING + "  " + error + "\n" + AnsiTheme.RESET);
                return;
            }
            String label = level != null ? level.name().toLowerCase(java.util.Locale.ROOT) : "off (provider default)";
            ui.printStreamingChunk(AnsiTheme.SUCCESS + "  \u2713 Reasoning effort set to " + label + "\n" + AnsiTheme.RESET);
            ui.printStreamingChunk(AnsiTheme.MUTED + "  Restart CLI for the change to take effect.\n" + AnsiTheme.RESET);
            return;
        }
        // interactive picker
        var current = loadReasoningEffortFromExtraBody();
        String[] levels = {"low", "medium", "high", "max", "off (provider default)"};
        var labels = new java.util.ArrayList<String>(5);
        for (String l : levels) {
            boolean isCurrent = (l.startsWith("off") && current == null)
                    || (current != null && l.equalsIgnoreCase(current.name()));
            labels.add(l + (isCurrent ? " (current)" : ""));
        }
        ui.printStreamingChunk("\n" + AnsiTheme.PROMPT + "Reasoning Effort" + AnsiTheme.RESET
                + AnsiTheme.MUTED + " (\u2191\u2193 select, Enter confirm, q/Esc cancel)" + AnsiTheme.RESET + "\n");
        int choice = ui.pickIndex(labels);
        if (choice < 0) return;
        var newLevel = switch (choice) {
            case 0 -> ReasoningEffort.LOW;
            case 1 -> ReasoningEffort.MEDIUM;
            case 2 -> ReasoningEffort.HIGH;
            case 3 -> ReasoningEffort.MAX;
            default -> null;
        };
        String error = persistReasoningEffortToExtraBody(newLevel);
        if (error != null) {
            ui.printStreamingChunk(AnsiTheme.WARNING + "  " + error + "\n" + AnsiTheme.RESET);
            return;
        }
        String label = newLevel != null ? newLevel.name().toLowerCase(java.util.Locale.ROOT) : "off (provider default)";
        ui.printStreamingChunk(AnsiTheme.SUCCESS + "  \u2713 Reasoning effort set to " + label + "\n" + AnsiTheme.RESET);
        ui.printStreamingChunk(AnsiTheme.MUTED + "  Restart CLI for the change to take effect.\n" + AnsiTheme.RESET);
    }

    // read active provider from agent.properties, merge reasoning_effort into its extra_body
    // returns null on success, or error message on failure
    public static String persistReasoningEffortToExtraBody(ReasoningEffort level) {
        try {
            var props = loadAgentProperties();
            String activeProvider = props.getProperty("active.provider");
            if (activeProvider == null || activeProvider.isBlank()) {
                return "No active.provider in agent.properties";
            }
            String key = activeProvider + ".request.extra_body";
            String existingJson = props.getProperty(key, "{}").trim();
            if (existingJson.isEmpty()) existingJson = "{}";
            @SuppressWarnings("unchecked")
            var extraMap = (java.util.Map<String, Object>) JsonUtil.fromJson(java.util.Map.class, existingJson);
            if (level == null) {
                extraMap.remove("reasoning_effort");
            } else {
                extraMap.put("reasoning_effort", level.name().toLowerCase(java.util.Locale.ROOT));
            }
            Files.createDirectories(CONFIG_FILE.getParent());
            writePropertyToFile(key, extraMap.isEmpty() ? null : JsonUtil.toJson(extraMap));
            return null;
        } catch (IOException e) {
            LOGGER.warn("Failed to persist reasoning effort to extra_body", e);
            return "Failed to write config: " + e.getMessage();
        }
    }

    // write a single property key=value line to agent.properties, removing the line if value is null.
    // avoids Properties.store() which escapes = and : inside JSON values.
    private static void writePropertyToFile(String key, String value) throws IOException {
        var lines = new java.util.ArrayList<String>();
        boolean found = false;
        if (Files.exists(CONFIG_FILE)) {
            for (String line : Files.readAllLines(CONFIG_FILE)) {
                String stripped = line.stripLeading();
                if (!found && isPropertyLine(stripped, key)) {
                    found = true;
                    if (value != null) {
                        lines.add(key + "=" + value);
                    }
                    // if value is null, skip this line (remove)
                } else {
                    lines.add(line);
                }
            }
        }
        if (!found && value != null) {
            lines.add(key + "=" + value);
        }
        Files.write(CONFIG_FILE, lines);
    }

    private static boolean isPropertyLine(String line, String key) {
        int sep = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '=' || c == ':') { sep = i; break; }
        }
        if (sep < 0) return false;
        String lineKey = line.substring(0, sep);
        // unescape any Properties-escaped chars in the key
        var sb = new StringBuilder();
        for (int i = 0; i < lineKey.length(); i++) {
            char c = lineKey.charAt(i);
            if (c == '\\' && i + 1 < lineKey.length()) {
                sb.append(lineKey.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().equals(key);
    }

    public static ReasoningEffort loadReasoningEffortFromExtraBody() {
        try {
            var props = loadAgentProperties();
            String activeProvider = props.getProperty("active.provider");
            if (activeProvider == null || activeProvider.isBlank()) return null;
            String key = activeProvider + ".request.extra_body";
            String json = props.getProperty(key, "{}").trim();
            if (json.isEmpty() || "{}".equals(json)) return null;
            @SuppressWarnings("unchecked")
            var extraMap = (java.util.Map<String, Object>) JsonUtil.fromJson(java.util.Map.class, json);
            Object effort = extraMap.get("reasoning_effort");
            if (effort instanceof String s) {
                return parseLevel(s.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read reasoning effort from extra_body", e);
        }
        return null;
    }

    private static Properties loadAgentProperties() throws IOException {
        var props = new Properties();
        if (Files.exists(CONFIG_FILE)) {
            try (InputStream is = Files.newInputStream(CONFIG_FILE)) {
                props.load(is);
            }
        }
        return props;
    }

    public static ReasoningEffort parseLevel(String level) {
        return switch (level) {
            case "low" -> ReasoningEffort.LOW;
            case "medium" -> ReasoningEffort.MEDIUM;
            case "high" -> ReasoningEffort.HIGH;
            case "max" -> ReasoningEffort.MAX;
            default -> null;
        };
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
            var os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("clip");
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("pbcopy");
            } else {
                // Linux: prefer wl-copy (Wayland), fall back to xclip
                pb = new ProcessBuilder("sh", "-c", "if command -v wl-copy >/dev/null 2>&1; then wl-copy; elif command -v xclip >/dev/null 2>&1; then xclip -selection clipboard; else echo 'No clipboard tool found' >&2; exit 1; fi");
            }
            var process = pb.start();
            try (var out = process.getOutputStream()) {
                out.write(lastAssistant.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            process.waitFor();
            if (process.exitValue() != 0) {
                throw new java.io.IOException("Clipboard command exited with " + process.exitValue());
            }
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "\u2713" + AnsiTheme.RESET + " Copied to clipboard (" + lastAssistant.length() + " chars)\n\n");
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
        if (memoryCommand != null) {
            MemorySectionManager.reloadAgentMemorySection(agent, memoryCommand.getMemoryProvider());
        }
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
                         boolean memoryEnabled, boolean dailyLogsEnabled,
                         boolean promptExtractionEnabled, Integer timeLimitSeconds,
                         String defaultServerUrl) {
    }
}
