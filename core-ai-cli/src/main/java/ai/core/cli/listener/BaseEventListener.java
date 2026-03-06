package ai.core.cli.listener;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.StreamingMarkdownRenderer;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.ui.ThinkingSpinner;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class BaseEventListener implements AgentEventListener {
    protected final TerminalUI ui;
    protected final AgentSession session;
    protected final StreamingMarkdownRenderer markdownRenderer;
    protected final ThinkingSpinner spinner;
    protected volatile CompletableFuture<Void> turnFuture;
    protected final AtomicBoolean spinnerActive = new AtomicBoolean(false);
    protected volatile boolean turnTextStarted;
    private final AtomicReference<TurnCompleteEvent> lastTurnComplete = new AtomicReference<>();

    protected BaseEventListener(TerminalUI ui, AgentSession session) {
        this.ui = ui;
        this.session = session;
        this.markdownRenderer = new StreamingMarkdownRenderer(ui.getWriter(), ui.isAnsiSupported(), ui::getTerminalWidth);
        this.spinner = new ThinkingSpinner(ui.getWriter(), ui::getTerminalWidth);
    }

    public void prepareTurn() {
        turnFuture = new CompletableFuture<>();
        turnTextStarted = false;
        spinner.resetTimer();
    }

    public void waitForTurn() {
        if (turnFuture != null) turnFuture.join();
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        stopSpinnerIfActive();
        if (!turnTextStarted) {
            turnTextStarted = true;
            ui.getWriter().println("\n" + AnsiTheme.SEPARATOR + "\u23FA" + AnsiTheme.RESET);
        }
        markdownRenderer.processChunk(event.chunk);
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        stopSpinnerIfActive();
        ui.printStreamingChunk(AnsiTheme.REASONING + event.chunk + AnsiTheme.RESET);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        stopSpinnerIfActive();
        markdownRenderer.flush();
        ui.showToolStart(event.toolName, event.arguments);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        ui.showToolResult(event.toolName, event.status, event.result);
        startSpinner();
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        stopSpinnerIfActive();
        var decision = ui.askPermission(event.toolName, event.arguments);
        session.approveToolCall(event.callId, decision);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        stopSpinnerIfActive();
        markdownRenderer.flush();
        markdownRenderer.reset();
        if (Boolean.TRUE.equals(event.cancelled)) {
            ui.getWriter().println("\n" + AnsiTheme.WARNING + "[Cancelled]" + AnsiTheme.RESET);
            ui.getWriter().flush();
        }
        lastTurnComplete.set(event);
        printTurnSummary();
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onError(ErrorEvent event) {
        stopSpinnerIfActive();
        markdownRenderer.flush();
        markdownRenderer.reset();
        ui.showError(event.message);
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        if (event.status == SessionStatus.RUNNING) {
            startSpinner();
        }
    }

    protected void printTurnSummary() {
        long elapsed = spinner.getElapsedMs();
        String time = ThinkingSpinner.formatElapsed(elapsed);
        var event = lastTurnComplete.get();
        var sb = new StringBuilder();
        sb.append("\n").append(AnsiTheme.MUTED).append("  \u2726 ").append(time);
        if (event != null && event.inputTokens != null && event.outputTokens != null) {
            long total = event.inputTokens + event.outputTokens;
            sb.append(String.format(" | %,d tokens (\u2191 %,d \u2193 %,d)", total, event.inputTokens, event.outputTokens));
        }
        sb.append(AnsiTheme.RESET);
        ui.getWriter().println(sb.toString());
        ui.getWriter().flush();
    }

    protected void startSpinner() {
        if (spinnerActive.compareAndSet(false, true)) {
            spinner.start();
        }
    }

    protected void stopSpinnerIfActive() {
        if (spinnerActive.compareAndSet(true, false)) {
            spinner.stop();
        }
    }
}
