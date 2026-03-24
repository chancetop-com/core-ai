package ai.core.cli.listener;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.OnToolEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.cli.ui.OutputPanel;
import ai.core.cli.ui.TerminalUI;
import ai.core.tool.tools.TaskTool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class BaseEventListener implements AgentEventListener {
    protected final TerminalUI ui;
    protected final AgentSession session;
    protected final OutputPanel panel;
    protected volatile CompletableFuture<Void> turnFuture;
    private final AtomicReference<TurnCompleteEvent> lastTurnComplete = new AtomicReference<>();

    protected BaseEventListener(TerminalUI ui, AgentSession session) {
        this.ui = ui;
        this.session = session;
        this.panel = new OutputPanel(ui.getWriter(), ui.isAnsiSupported(), ui::getTerminalWidth);
    }

    public void prepareTurn() {
        turnFuture = new CompletableFuture<>();
        panel.beginTurn();
    }

    public void waitForTurn() {
        if (turnFuture != null) turnFuture.join();
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        panel.streamText(event.chunk);
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        panel.streamReasoning(event.chunk);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        panel.toolStart(event.toolName, event.arguments, event.diff);
        if (TaskTool.TOOL_NAME.equals(event.toolName)) {
            panel.enterTask(event.toolName);
        }
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        if (TaskTool.TOOL_NAME.equals(event.toolName)) {
            panel.exitTask();
            panel.toolResult(event.status, "Done");
        } else {
            panel.toolResult(event.status, event.result);
        }

    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        panel.stopSpinnerIfActive();
        panel.getMarkdownRenderer().flush();
        var decision = ui.askPermission(event.toolName, event.arguments, event.suggestedPattern);
        session.approveToolCall(event.callId, decision);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        panel.endTurn();
        if (Boolean.TRUE.equals(event.cancelled)) {
            panel.cancelled();
        }
        if (Boolean.TRUE.equals(event.maxTurnsReached)) {
            panel.maxTurnsReached();
        }
        lastTurnComplete.set(event);
        printTurnSummary();
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onError(ErrorEvent event) {
        panel.error(event.message);
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        if (event.status == SessionStatus.RUNNING) {
            panel.startSpinner();
        }
    }

    protected void printTurnSummary() {
        long elapsed = panel.getSpinner().getElapsedMs();
        var event = lastTurnComplete.get();
        if (event != null && event.inputTokens != null && event.outputTokens != null) {
            panel.turnSummary(elapsed, event.inputTokens, event.outputTokens);
        } else {
            panel.turnSummary(elapsed, null, null);
        }
    }

    @Override
    public void onOnTool(OnToolEvent event) {
        panel.startSpinner();
    }
}
