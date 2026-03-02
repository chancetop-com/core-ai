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
import org.jline.terminal.Terminal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author stephen
 */
public class CliEventListener implements AgentEventListener {

    private static final long CANCEL_WINDOW_MS = 3000;

    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...(" + text.length() + " chars)";
    }

    private final TerminalUI ui;
    private final AgentSession session;
    private final StreamingMarkdownRenderer markdownRenderer;
    private volatile CompletableFuture<Void> turnFuture;
    private final AtomicBoolean turnRunning = new AtomicBoolean(false);
    private volatile long firstInterrupt;
    private Terminal.SignalHandler previousIntHandler;

    public CliEventListener(TerminalUI ui, AgentSession session) {
        this.ui = ui;
        this.session = session;
        this.markdownRenderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported());
        installSignalHandler();
    }

    public void prepareTurn() {
        turnFuture = new CompletableFuture<>();
        turnRunning.set(true);
        firstInterrupt = 0;
    }

    public void waitForTurn() {
        if (turnFuture != null) turnFuture.join();
        turnRunning.set(false);
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
        markdownRenderer.flush();
        ui.showToolStart(event.toolName, event.arguments);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        DebugLog.log("tool result: " + event.toolName + " callId=" + event.callId + " status=" + event.status + " result=" + truncate(event.result, 200));
        ui.showToolResult(event.toolName, event.status, event.result);
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

    private void installSignalHandler() {
        Terminal terminal = ui.getTerminal();
        if (terminal == null) {
            return;
        }
        previousIntHandler = terminal.handle(Terminal.Signal.INT, signal -> {
            if (turnRunning.get()) {
                handleInterruptDuringTurn();
            } else if (previousIntHandler != null) {
                previousIntHandler.handle(signal);
            }
        });
    }

    private void handleInterruptDuringTurn() {
        long now = System.currentTimeMillis();
        if (now - firstInterrupt <= CANCEL_WINDOW_MS && firstInterrupt > 0) {
            DebugLog.log("double Ctrl+C, cancelling turn");
            session.cancelTurn();
            firstInterrupt = 0;
        } else {
            firstInterrupt = now;
            ui.getWriter().println("\n" + AnsiTheme.MUTED + "[Press Ctrl+C again to cancel]" + AnsiTheme.RESET);
            ui.getWriter().flush();
        }
    }
}
