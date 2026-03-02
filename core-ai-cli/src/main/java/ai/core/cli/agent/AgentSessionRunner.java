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
            if ("/resume".equalsIgnoreCase(trimmed)) {
                String picked = showSessionPicker();
                if (picked != null) {
                    switchSessionId.set(picked);
                    queue.offer(POISON_PILL);
                    break;
                }
                readyForInput.release();
                continue;
            }
            if (trimmed.startsWith("/")) {
                commands.handle(input);
                showFrame = true;
                readyForInput.release();
                continue;
            }
            showFrame = true;
            queue.offer(input);
        }
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
