package ai.core.tool;

import ai.core.llm.domain.FunctionCall;

import java.time.Instant;

/**
 * Persisted shape of one async task. Rebuilt from the task facts on every poll; the delivery fields
 * (who owns it, what it still owes) are carried over instead of recomputed.
 *
 * @author stephen
 */
public class AsyncTaskData {
    static AsyncTaskData from(ToolCallAsyncTask task) {
        var data = new AsyncTaskData();
        data.taskId = task.taskId();
        data.toolName = task.tool().getName();
        data.originalCall = task.originalCall();
        data.status = task.status();
        data.createdAtMs = task.createdAt().toEpochMilli();
        data.lastPolledAtMs = task.lastPolledAt() != null ? task.lastPolledAt().toEpochMilli() : null;
        data.pollCount = task.pollCount();
        data.lastMessage = task.lastResult() != null ? task.lastResult().getResult() : null;
        var stats = task.lastResult() != null ? task.lastResult().getStats() : null;
        data.sandboxTask = stats != null && Boolean.parseBoolean(String.valueOf(stats.get("sandboxTask")));
        data.sandboxTaskId = stats != null && stats.get("sandboxTaskId") != null
                ? String.valueOf(stats.get("sandboxTaskId")) : null;
        return data;
    }

    public String taskId;
    public String toolName;
    public String sessionId;
    public FunctionCall originalCall;
    public ToolCallResult.Status status;
    public Long createdAtMs;
    public Long lastPolledAtMs;
    public int pollCount;
    /** text of the last result; ToolCallResult itself is not JSON-friendly, so status + text are what survive the store */
    public String lastMessage;
    /** true when this task runs inside a sandbox runtime and must be polled through Sandbox.pollTask */
    public Boolean sandboxTask;
    /** runtime's own task id (only meaningful with sandboxTask); never shown to the agent */
    public String sandboxTaskId;
    /** set once the owning session has accepted the terminal notification; null means still owed */
    public Long notifiedAtMs;
    public int notifyAttempts;
    public Long lastNotifyAttemptAtMs;
    /** terminal sub-status when FAILED represents a cancellation (e.g. "cancelled") */
    public String taskStatus;

    /** {@link #from} rebuilds the task facts only; who it belongs to and what it still owes carries over. */
    void inheritDelivery(AsyncTaskData previous) {
        sessionId = previous.sessionId;
        notifiedAtMs = previous.notifiedAtMs;
        notifyAttempts = previous.notifyAttempts;
        lastNotifyAttemptAtMs = previous.lastNotifyAttemptAtMs;
        sandboxTask = previous.sandboxTask;
        sandboxTaskId = previous.sandboxTaskId;
        taskStatus = previous.taskStatus;
    }

    ToolCallAsyncTask toTask(ToolCall tool) {
        return new ToolCallAsyncTask(
            taskId,
            tool,
            originalCall,
            status,
            Instant.ofEpochMilli(createdAtMs),
            lastPolledAtMs != null ? Instant.ofEpochMilli(lastPolledAtMs) : null,
            pollCount,
            restoredResult()
        );
    }

    ToolCallResult restoredResult() {
        var message = lastMessage != null ? lastMessage : "";
        if (status == null) return ToolCallResult.pending(taskId, message);
        var result = switch (status) {
            case COMPLETED -> ToolCallResult.completed(message);
            case FAILED -> ToolCallResult.failed(message);
            case WAITING_FOR_INPUT -> ToolCallResult.waitingForInput(taskId, message);
            default -> ToolCallResult.pending(taskId, message);
        };
        if (taskStatus != null && !taskStatus.isBlank()) {
            result.withStats("taskStatus", taskStatus);
        }
        return result;
    }
}

