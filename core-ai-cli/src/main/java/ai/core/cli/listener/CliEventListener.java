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
import ai.core.cli.ui.TerminalUI;
import java.util.concurrent.CompletableFuture;

/**
 * @author stephen
 */
public class CliEventListener implements AgentEventListener {
    private final TerminalUI ui;
    private final AgentSession session;
    private volatile CompletableFuture<Void> turnFuture;

    public CliEventListener(TerminalUI ui, AgentSession session) {
        this.ui = ui;
        this.session = session;
    }

    public void prepareTurn() {
        turnFuture = new CompletableFuture<>();
    }

    public void waitForTurn() {
        if (turnFuture != null) turnFuture.join();
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        DebugLog.log("text chunk: length=" + event.chunk.length());
        ui.printStreamingChunk(event.chunk);
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        DebugLog.log("reasoning chunk: length=" + event.chunk.length());
        ui.printStreamingChunk("\u001B[2m" + event.chunk + "\u001B[0m");
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
        DebugLog.log("turn complete");
        ui.endStreaming();
        ui.printSeparator();
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onError(ErrorEvent event) {
        DebugLog.log("error: " + event.message);
        ui.showError(event.message);
        if (turnFuture != null) turnFuture.complete(null);
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        DebugLog.log("status change: " + event.status);
        ui.showStatus(event.status);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...(" + text.length() + " chars)";
    }
}
