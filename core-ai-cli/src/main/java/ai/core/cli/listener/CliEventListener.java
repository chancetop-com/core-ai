package ai.core.cli.listener;

import ai.core.api.session.AgentEventListener;
import ai.core.api.session.AgentSession;
import ai.core.api.session.ErrorEvent;
import ai.core.api.session.ReasoningChunkEvent;
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
import java.util.concurrent.CompletableFuture;

/**
 * @author stephen
 */
public class CliEventListener implements AgentEventListener {

    private static final int ESC_KEY = 0x1B;
    private static final long ESC_WINDOW_MS = 3000;

    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...(" + text.length() + " chars)";
    }

    private final TerminalUI ui;
    private final AgentSession session;
    private final StreamingMarkdownRenderer markdownRenderer;
    private volatile CompletableFuture<Void> turnFuture;
    private volatile Thread escThread;

    public CliEventListener(TerminalUI ui, AgentSession session) {
        this.ui = ui;
        this.session = session;
        this.markdownRenderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported());
    }

    public void prepareTurn() {
        turnFuture = new CompletableFuture<>();
        startEscMonitor();
    }

    public void waitForTurn() {
        if (turnFuture != null) turnFuture.join();
        stopEscMonitor();
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        DebugLog.log("text chunk: length=" + event.chunk.length());
        markdownRenderer.processChunk(event.chunk);
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        DebugLog.log("reasoning chunk: length=" + event.chunk.length());
        ui.printStreamingChunk(AnsiTheme.REASONING + event.chunk + AnsiTheme.RESET);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        DebugLog.log("tool start: " + event.toolName + " callId=" + event.callId + " args=" + truncate(event.arguments, 200));
        ui.showToolStart(event.toolName, event.arguments);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        DebugLog.log("tool result: " + event.toolName + " callId=" + event.callId + " status=" + event.status + " result=" + truncate(event.result, 200));
        ui.showToolResult(event.toolName, event.status);
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        DebugLog.log("tool approval request: " + event.toolName + " callId=" + event.callId);
        var decision = ui.askPermission(event.toolName, event.arguments);
        DebugLog.log("tool approval decision: " + event.toolName + " callId=" + event.callId + " decision=" + decision);
        session.approveToolCall(event.callId, decision);
        DebugLog.log("tool approval sent: " + event.toolName + " callId=" + event.callId);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        DebugLog.log("turn complete, cancelled=" + event.cancelled);
        markdownRenderer.flush();
        markdownRenderer.reset();
        if (Boolean.TRUE.equals(event.cancelled)) {
            ui.getWriter().println("\n" + AnsiTheme.WARNING + "[Cancelled]" + AnsiTheme.RESET);
            ui.getWriter().flush();
        }
        ui.endStreaming();
        ui.printSeparator();
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onError(ErrorEvent event) {
        DebugLog.log("error: " + event.message);
        markdownRenderer.flush();
        markdownRenderer.reset();
        ui.showError(event.message);
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        DebugLog.log("status change: " + event.status);
        ui.showStatus(event.status);
    }

    private void startEscMonitor() {
        if (!ui.isJLineEnabled()) {
            return;
        }
        Thread thread = new Thread(() -> {
            DebugLog.log("ESC monitor started");
            long firstEsc = 0;
            while (!Thread.currentThread().isInterrupted()) {
                int key = ui.readRawKey();
                if (key == ESC_KEY) {
                    long now = System.currentTimeMillis();
                    if (now - firstEsc <= ESC_WINDOW_MS) {
                        DebugLog.log("double ESC, cancelling turn");
                        session.cancelTurn();
                        break;
                    }
                    firstEsc = now;
                    ui.getWriter().print(AnsiTheme.MUTED + " [Press ESC again to cancel]" + AnsiTheme.RESET);
                    ui.getWriter().flush();
                }
            }
            DebugLog.log("ESC monitor stopped");
        }, "esc-monitor");
        thread.setDaemon(true);
        escThread = thread;
        thread.start();
    }

    private void stopEscMonitor() {
        Thread thread = escThread;
        escThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
