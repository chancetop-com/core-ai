package ai.core.cli.listener;

import ai.core.agent.Agent;
import ai.core.api.session.AgentEventListener;
import ai.core.api.session.AgentSession;
import ai.core.api.session.ErrorEvent;
import ai.core.api.session.ReasoningChunkEvent;
import ai.core.api.session.SessionStatus;
import ai.core.api.session.StatusChangeEvent;
import ai.core.api.session.TextChunkEvent;
import ai.core.api.session.ToolApprovalRequestEvent;
import ai.core.api.session.ToolResultEvent;
import ai.core.api.session.ToolStartEvent;
import ai.core.api.session.TurnCompleteEvent;
import ai.core.cli.DebugLog;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.StreamingMarkdownRenderer;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.ui.ThinkingSpinner;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author stephen
 */
public class CliEventListener implements AgentEventListener {

    private static final int ESC = 27;

    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...(" + text.length() + " chars)";
    }

    private final TerminalUI ui;
    private final AgentSession session;
    private final Agent agent;
    private final StreamingMarkdownRenderer markdownRenderer;
    private final ThinkingSpinner spinner;
    private volatile CompletableFuture<Void> turnFuture;
    private final AtomicBoolean turnRunning = new AtomicBoolean(false);
    private final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    private volatile long turnTokensBefore;
    private Thread escReaderThread;

    public CliEventListener(TerminalUI ui, AgentSession session, Agent agent) {
        this.ui = ui;
        this.session = session;
        this.agent = agent;
        this.markdownRenderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported(), ui.getTerminalWidth());
        this.spinner = new ThinkingSpinner(ui.getWriter());
    }

    public void prepareTurn() {
        turnFuture = new CompletableFuture<>();
        turnRunning.set(true);
        turnTokensBefore = agent.getCurrentTokenUsage().getTotalTokens();
        startEscReader();
    }

    public void waitForTurn() {
        if (turnFuture != null) turnFuture.join();
        turnRunning.set(false);
        stopEscReader();
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        DebugLog.log("text chunk: length=" + event.chunk.length());
        stopSpinnerIfActive();
        markdownRenderer.processChunk(event.chunk);
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        DebugLog.log("reasoning chunk: length=" + event.chunk.length());
        stopSpinnerIfActive();
        ui.printStreamingChunk(AnsiTheme.REASONING + event.chunk + AnsiTheme.RESET);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        DebugLog.log("tool start: " + event.toolName + " callId=" + event.callId + " args=" + truncate(event.arguments, 200));
        stopSpinnerIfActive();
        markdownRenderer.flush();
        ui.showToolStart(event.toolName, event.arguments);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        DebugLog.log("tool result: " + event.toolName + " callId=" + event.callId + " status=" + event.status + " result=" + truncate(event.result, 200));
        ui.showToolResult(event.toolName, event.status, event.result);
        startSpinner();
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        DebugLog.log("tool approval request: " + event.toolName + " callId=" + event.callId);
        stopSpinnerIfActive();
        var decision = ui.askPermission(event.toolName, event.arguments);
        DebugLog.log("tool approval decision: " + event.toolName + " callId=" + event.callId + " decision=" + decision);
        session.approveToolCall(event.callId, decision);
        DebugLog.log("tool approval sent: " + event.toolName + " callId=" + event.callId);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        DebugLog.log("turn complete, cancelled=" + event.cancelled);
        stopSpinnerIfActive();
        markdownRenderer.flush();
        markdownRenderer.reset();
        if (Boolean.TRUE.equals(event.cancelled)) {
            ui.getWriter().println("\n" + AnsiTheme.WARNING + "[Cancelled]" + AnsiTheme.RESET);
            ui.getWriter().flush();
        }
        printTurnSummary();
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onError(ErrorEvent event) {
        DebugLog.log("error: " + event.message);
        stopSpinnerIfActive();
        markdownRenderer.flush();
        markdownRenderer.reset();
        ui.showError(event.message);
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        DebugLog.log("status change: " + event.status);
        if (event.status == SessionStatus.RUNNING) {
            startSpinner();
        }
    }

    private void startSpinner() {
        if (spinnerActive.compareAndSet(false, true)) {
            spinner.start();
        }
    }

    private void stopSpinnerIfActive() {
        if (spinnerActive.compareAndSet(true, false)) {
            spinner.stop();
        }
    }

    private void printTurnSummary() {
        long elapsed = spinner.getElapsedMs();
        long totalNow = agent.getCurrentTokenUsage().getTotalTokens();
        long turnTokens = totalNow - turnTokensBefore;
        String time = ThinkingSpinner.formatElapsed(elapsed);
        ui.getWriter().println("\n" + AnsiTheme.MUTED + "  ✻ " + time
                + " | " + String.format("%,d", turnTokens) + " tokens"
                + AnsiTheme.RESET);
        ui.getWriter().flush();
    }

    private void startEscReader() {
        var ttyFile = new File("/dev/tty");
        if (!ttyFile.exists()) return;
        escReaderThread = new Thread(() -> {
            stty("-icanon", "-echo");
            boolean escPressed = pollEscKey(ttyFile);
            stty("sane");
            if (escPressed) {
                DebugLog.log("ESC pressed, cancelling turn");
                session.cancelTurn();
            }
        }, "esc-reader");
        escReaderThread.setDaemon(true);
        escReaderThread.start();
    }

    @SuppressWarnings("PMD.AvoidFileStream")
    private boolean pollEscKey(File ttyFile) {
        try (var ttyIn = new FileInputStream(ttyFile)) {
            byte[] buf = new byte[8];
            while (turnRunning.get() && !Thread.currentThread().isInterrupted()) {
                if (ttyIn.available() > 0) {
                    int n = ttyIn.read(buf);
                    if (n > 0 && buf[0] == ESC && n == 1) {
                        return true;
                    }
                } else {
                    Thread.sleep(50);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            DebugLog.log("esc reader error: " + e.getMessage());
        }
        return false;
    }

    private void stopEscReader() {
        if (escReaderThread != null) {
            escReaderThread.interrupt();
            escReaderThread = null;
        }
        stty("sane");
    }

    private void stty(String... args) {
        try {
            var cmd = new String[args.length + 1];
            cmd[0] = "stty";
            System.arraycopy(args, 0, cmd, 1, args.length);
            new ProcessBuilder(cmd)
                    .redirectInput(new File("/dev/tty"))
                    .redirectOutput(new File("/dev/tty"))
                    .start()
                    .waitFor();
        } catch (Exception e) {
            DebugLog.log("stty failed: " + e.getMessage());
        }
    }
}
