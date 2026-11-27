package ai.core.session;

import ai.core.tool.ToolCallResult;

/**
 * A settled background task, kept after it finishes so a late poll still gets the answer.
 *
 * @author stephen
 */
record TerminalTask(String status, String result, String error, String outputRef, long completedAtMs) {
    TerminalTask(String status, String result, String error, String outputRef) {
        this(status, result, error, outputRef, System.currentTimeMillis());
    }

    ToolCallResult toResult() {
        return "completed".equals(status)
                ? ToolCallResult.completed(result != null ? result : "")
                : ToolCallResult.failed(error != null ? error : status);
    }
}
