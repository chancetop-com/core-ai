package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.command.HandlerContext;
import ai.core.cli.command.MemoryCommandHandler;
import ai.core.cli.command.ReplCommandHandler;
import ai.core.cli.config.ModelRegistry;
import ai.core.cli.hook.ScriptHookLifecycle;
import ai.core.cli.listener.CliEventListener;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemoryTriggerService;
import ai.core.cli.memory.SessionCloseExtractor;
import ai.core.cli.remote.RemoteConfig;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.BannerPrinter;
import ai.core.cli.ui.FileReferenceExpander;
import ai.core.cli.ui.OutputPanel;
import ai.core.cli.ui.StreamingMarkdownRenderer;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.upgrade.VersionUtil;
import ai.core.llm.LLMProviders;
import ai.core.llm.domain.RoleType;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
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
        var sessionHandler = new AgentSessionRunnerCommandHandler(
                ui, agent, sessionId, sessionManager, modelPicker, memoryCommand);
        var dispatcher = new CommandDispatcher(
                ui, modelPicker, switchSessionId,
                new HandlerContext(commands, memoryCommand, memoryEnabled), sessionHandler,
                defaultServerUrl, agent.getExecutionContext().getAgentProfileRegistry(), workspace);
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

    public record Config(String modelName, boolean autoApproveAll, String sessionId,
                         SessionManager sessionManager, ToolPermissionStore permissionStore,
                         MdMemoryProvider memory, ModelRegistry modelRegistry,
                         SessionPersistence sessionPersistence,
                         boolean memoryEnabled, boolean dailyLogsEnabled,
                         boolean promptExtractionEnabled, Integer timeLimitSeconds,
                         String defaultServerUrl) {
    }
}
