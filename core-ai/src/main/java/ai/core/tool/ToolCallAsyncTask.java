package ai.core.tool;

import ai.core.llm.domain.FunctionCall;

import java.time.Instant;

/**
 * @author stephen
 */
public record ToolCallAsyncTask(
        String taskId,
        ToolCall tool,
        FunctionCall originalCall,
        ToolCallResult.Status status,
        Instant createdAt,
        Instant lastPolledAt,
        int pollCount,
        ToolCallResult lastResult
) {
    public ToolCallAsyncTask(String taskId, ToolCall tool, FunctionCall originalCall, ToolCallResult initialResult) {
        this(taskId, tool, originalCall, initialResult.getStatus(), Instant.now(), null, 0, initialResult);
    }

    public ToolCallAsyncTask withPolled(ToolCallResult result) {
        return new ToolCallAsyncTask(taskId, tool, originalCall, result.getStatus(), createdAt, Instant.now(), pollCount + 1, result);
    }

    public ToolCallAsyncTask withStatus(ToolCallResult.Status status) {
        return new ToolCallAsyncTask(taskId, tool, originalCall, status, createdAt, lastPolledAt, pollCount, lastResult);
    }

    public boolean isPending() {
        return status == ToolCallResult.Status.PENDING;
    }

    public boolean isWaitingForInput() {
        return status == ToolCallResult.Status.WAITING_FOR_INPUT;
    }

    public boolean isTerminal() {
        return status == ToolCallResult.Status.COMPLETED || status == ToolCallResult.Status.FAILED;
    }

    public long getElapsedMs() {
        return java.time.Duration.between(createdAt, Instant.now()).toMillis();
    }
}
