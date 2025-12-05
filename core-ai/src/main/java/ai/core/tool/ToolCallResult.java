package ai.core.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public final class ToolCallResult {

    public static ToolCallResult completed(String result) {
        var r = new ToolCallResult();
        r.status = Status.COMPLETED;
        r.result = result;
        return r;
    }

    public static ToolCallResult failed(String error) {
        return failed(error, new RuntimeException(error));
    }

    public static ToolCallResult failed(String error, Throwable e) {
        return failed(error, new RuntimeException(e));
    }

    public static ToolCallResult failed(String error, RuntimeException e) {
        var r = new ToolCallResult();
        r.status = Status.FAILED;
        r.result = error;
        r.runtimeException = e;
        return r;
    }

    private Status status;
    private String taskId;
    private String result;

    private String toolName;
    private long durationMs;
    private final Map<String, Object> stats;
    private RuntimeException runtimeException;

    private ToolCallResult() {
        this.stats = new HashMap<>();
    }

    public boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public boolean isWaitingForInput() {
        return status == Status.WAITING_FOR_INPUT;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED;
    }

    public Status getStatus() {
        return status;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getResult() {
        return result;
    }

    public String getToolName() {
        return toolName;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Map<String, Object> getStats() {
        return stats;
    }

    public ToolCallResult withToolName(String toolName) {
        this.toolName = toolName;
        return this;
    }

    public ToolCallResult withDuration(long durationMs) {
        this.durationMs = durationMs;
        return this;
    }

    public ToolCallResult withResult(String result) {
        this.result = result;
        return this;
    }

    public ToolCallResult withStats(String key, Object value) {
        this.stats.put(key, value);
        return this;
    }

    public ToolCallResult withStats(Map<String, Object> stats) {
        this.stats.putAll(stats);
        return this;
    }

    public RuntimeException getRuntimeException() {
        return runtimeException;
    }

    public ToolCallResult withRuntimeException(RuntimeException runtimeException) {
        this.runtimeException = runtimeException;
        return this;
    }

    public String toResultForLLM() {
        return switch (status) {
            case COMPLETED -> result;
            case PENDING -> formatPendingMessage();
            case WAITING_FOR_INPUT -> formatWaitingMessage();
            case FAILED -> "Error: " + result;
        };
    }

    private String formatPendingMessage() {
        var sb = new StringBuilder(160);
        sb.append("[ASYNC_TASK_STARTED] taskId=").append(taskId).append('\n');
        if (result != null) {
            sb.append(result).append('\n');
        }
        sb.append("The task is running asynchronously. Use 'async_task_output' tool to check progress or wait for completion.");
        return sb.toString();
    }

    private String formatWaitingMessage() {
        var sb = new StringBuilder(100);
        sb.append("[WAITING_FOR_INPUT] taskId=").append(taskId).append('\n');
        if (result != null) {
            sb.append(result).append('\n');
        }
        sb.append("Please ask the user to provide the required input.");
        return sb.toString();
    }


    public enum Status {
        COMPLETED,
        PENDING,
        WAITING_FOR_INPUT,
        FAILED
    }
}
