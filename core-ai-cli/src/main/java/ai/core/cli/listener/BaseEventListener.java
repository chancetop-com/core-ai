package ai.core.cli.listener;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.OnToolEvent;
import ai.core.api.server.session.PlanUpdateEvent;
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
import ai.core.cli.ui.ThinkingSpinner;
import ai.core.tool.tools.TaskTool;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
    private volatile long taskStartTime;
    private final AtomicInteger taskToolCallCount = new AtomicInteger(0);
    private volatile String currentAttributedTaskId;
    private final Map<String, String> asyncTaskDescriptions = new LinkedHashMap<>();

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
        String parentTaskId = event.parentTaskId;
        if (parentTaskId != null) {
            if (!parentTaskId.equals(currentAttributedTaskId)) {
                if (panel.isInTask()) panel.exitTask();
                panel.startAttributedTaskSection(parentTaskId, asyncTaskDescriptions.get(parentTaskId));
                currentAttributedTaskId = parentTaskId;
            }
            panel.toolStart(event.toolName, event.arguments, event.diff);
            return;
        }
        if (currentAttributedTaskId != null) {
            panel.exitTask();
            currentAttributedTaskId = null;
        }
        if (TaskTool.TOOL_NAME.equals(event.toolName) && panel.isInTask()) {
            panel.exitTask();
        }
        if (panel.isInTask()) {
            taskToolCallCount.incrementAndGet();
        }
        panel.toolStart(event.toolName, event.arguments, event.diff);
        if (TaskTool.TOOL_NAME.equals(event.toolName)) {
            panel.enterTask(event.toolName);
            taskStartTime = System.currentTimeMillis();
            taskToolCallCount.set(0);
        }
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        if (TaskTool.TOOL_NAME.equals(event.toolName)) {
            if ("async_launched".equals(event.status)) {
                try {
                    var map = JsonUtil.fromJson(Map.class, event.result);
                    var taskId = (String) map.get("taskId");
                    var description = (String) map.get("description");
                    if (taskId != null && description != null) {
                        asyncTaskDescriptions.put(taskId, description);
                    }
                } catch (Exception ignored) {
                }
                panel.asyncTaskLaunched(buildTaskAsyncSummary(event.result));
            } else {
                panel.exitTask();
                panel.toolResult(event.status, buildTaskDoneSummary(System.currentTimeMillis() - taskStartTime, taskToolCallCount.get()));
            }
        } else {
            panel.toolResult(event.status, event.result);
        }
    }

    private String buildTaskDoneSummary(long elapsedMs, int toolCallCount) {
        var sb = new StringBuilder(50);
        sb.append("Done | ").append(ThinkingSpinner.formatElapsed(elapsedMs));
        if (toolCallCount > 0) {
            sb.append(" | ").append(toolCallCount).append(toolCallCount == 1 ? " tool" : " tools");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String buildTaskAsyncSummary(String resultJson) {
        try {
            var map = JsonUtil.fromJson(Map.class, resultJson);
            var taskId = (String) map.get("taskId");
            var description = (String) map.get("description");
            var sb = new StringBuilder("Running in background");
            if (taskId != null) sb.append(" | ").append(taskId);
            if (description != null && !description.isBlank()) sb.append(" | ").append(description);
            return sb.toString();
        } catch (Exception e) {
            return "Running in background";
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
        if (panel.isInTask()) panel.exitTask();
        panel.endTurn();
        if (Boolean.TRUE.equals(event.cancelled)) {
            panel.cancelled();
        }
        if (Boolean.TRUE.equals(event.maxTurnsReached)) {
            panel.maxTurnsReached();
        }
        lastTurnComplete.set(event);
        printTurnSummary();
        currentAttributedTaskId = null;
        asyncTaskDescriptions.clear();
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

    @Override
    public void onPlanUpdate(PlanUpdateEvent event) {
        panel.planUpdate(event.todos);
    }
}
