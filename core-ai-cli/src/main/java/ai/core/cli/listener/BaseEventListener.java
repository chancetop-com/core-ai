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
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.cli.ui.OutputPanel;
import ai.core.cli.ui.TerminalUI;
import ai.core.cli.ui.ThinkingSpinner;
import ai.core.tool.tools.TaskTool;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author stephen
 */
public class BaseEventListener implements AgentEventListener {
    protected final TerminalUI ui;
    protected final AgentSession session;
    protected final OutputPanel panel;
    protected volatile CompletableFuture<Void> turnFuture;
    protected final AtomicReference<TurnCompleteEvent> lastTurnComplete = new AtomicReference<>();
    private final Map<String, RuntimeTask> runTasks = new ConcurrentHashMap<>();

    protected BaseEventListener(TerminalUI ui, AgentSession session) {
        this.ui = ui;
        this.session = session;
        this.panel = new OutputPanel(ui.getWriter(), ui.isAnsiSupported(), ui::getTerminalWidth);
    }

    public OutputPanel getPanel() {
        return panel;
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
        if (TaskTool.TOOL_NAME.equals(event.toolName)) {
            runTasks.put(event.taskId, new RuntimeTask(event.taskId, System.currentTimeMillis(), event.runInBackground, 0));
            panel.toolStart(event.toolName, event.arguments, event.diff, false);
        } else if (Objects.isNull(event.taskId)) {
            panel.toolStart(event.toolName, event.arguments, event.diff, false);
        } else {
            increaseToolCallCount(event.taskId);
            if (!isInBackgroundTask(event.taskId)) {
                panel.toolStart(event.toolName, event.arguments, event.diff, isInFrontTask(event.taskId));
            }
        }
    }

    private boolean isInBackgroundTask(String taskId) {
        return Optional.of(runTasks).map(m -> m.get(taskId)).map(RuntimeTask::runInBackground).orElse(false);
    }

    private boolean isInFrontTask(String taskId) {
        return Optional.of(runTasks).map(m -> m.get(taskId)).map(RuntimeTask::runInBackground).map(b -> !b).orElse(false);
    }

    private boolean isInTask(String taskId) {
        return isInBackgroundTask(taskId) || isInFrontTask(taskId);
    }

    private void removeTask(String taskId) {
        runTasks.remove(taskId);
    }

    public int getRunTasksCount() {
        return runTasks.size();
    }

    public int getRunTasksToolCount() {
        return runTasks.values().stream().mapToInt(t -> t.toolCallCount).sum();
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        if (TaskTool.TOOL_NAME.equals(event.toolName)) {
            if (isInBackgroundTask(event.taskId)) {
                panel.asyncTaskLaunched();
            }
            if (isInFrontTask(event.taskId)) {
                panel.toolResult(event.status, buildTaskDoneSummary(System.currentTimeMillis() - getTaskStartTime(event.taskId), getToolCallCount(event.taskId)));
                removeTask(event.taskId);
            }
        } else if (Objects.isNull(event.taskId) || !isInTask(event.taskId)) {
            panel.toolResult(event.status, event.result);
        }

    }

    @Override
    public void onEnvironmentOutput(EnvironmentOutputChunkEvent event) {
        panel.toolOutputChunk(event.chunk);
    }

    private void increaseToolCallCount(String taskId) {
        if (Objects.isNull(taskId)) {
            return;
        }
        runTasks.computeIfPresent(taskId, (_, v) -> v.withIncrementedToolCallCount());
    }

    private int getToolCallCount(String taskId) {
        return Optional.of(runTasks).map(m -> m.get(taskId)).map(RuntimeTask::toolCallCount).orElse(0);
    }

    private long getTaskStartTime(String taskId) {
        return Optional.of(runTasks).map(m -> m.get(taskId)).map(RuntimeTask::startTime).orElse(0L);
    }


    private String buildTaskDoneSummary(long elapsedMs, int toolCallCount) {
        var sb = new StringBuilder(50);
        sb.append("Done | ").append(ThinkingSpinner.formatElapsed(elapsedMs));
        if (toolCallCount > 0) {
            sb.append(" | ").append(toolCallCount).append(toolCallCount == 1 ? " tool" : " tools");
        }
        return sb.toString();
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
            panel.turnSummary(elapsed, event.inputTokens, event.outputTokens, null);
        } else {
            panel.turnSummary(elapsed, null, null, null);
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

    record RuntimeTask(String taskId, Long startTime, Boolean runInBackground, Integer toolCallCount) {
        public RuntimeTask withIncrementedToolCallCount() {
            return new RuntimeTask(this.taskId, this.startTime, runInBackground, this.toolCallCount + 1);
        }
    }
}
