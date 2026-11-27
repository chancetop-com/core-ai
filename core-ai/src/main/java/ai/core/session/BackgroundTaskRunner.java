package ai.core.session;

import ai.core.tool.subagent.SubagentOutputSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Executes a background agent runner against its {@link SubagentOutputSink}, isolating sink I/O
 * failures from task status so an upload failure never turns a completed run into a failed one.
 *
 * @author stephen
 */
final class BackgroundTaskRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundTaskRunner.class);
    private static final int MAX_TERMINAL_NOTE_LENGTH = 4000;

    static TaskRunResult run(Supplier<String> agentRunner, SubagentOutputSink sink, String taskId) {
        String status;
        String result = null;
        String error = null;
        try {
            result = agentRunner.get();
            safeWrite(sink, result != null ? result : "");
            status = "completed";
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                status = "cancelled";
                error = e.getMessage();
                LOGGER.debug("background task interrupted, taskId={}", taskId);
            } else {
                status = "failed";
                error = e.getMessage();
                LOGGER.warn("background task failed, taskId={}, error={}", taskId, e.getMessage());
            }
            writeTerminalNote(sink, status, error);
        } finally {
            safeClose(sink);
        }
        return new TaskRunResult(status, result, error);
    }

    private static void safeWrite(SubagentOutputSink sink, String content) {
        try {
            sink.write(content);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to write background task output, falling back to the notification result: {}", e.getMessage());
        }
    }

    private static void safeClose(SubagentOutputSink sink) {
        try {
            sink.close();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to close background task output sink: {}", e.getMessage());
        }
    }

    private static void writeTerminalNote(SubagentOutputSink sink, String status, String error) {
        try {
            var detail = error == null || error.isBlank() ? "no error detail" : error;
            if (detail.length() > MAX_TERMINAL_NOTE_LENGTH) {
                detail = detail.substring(0, MAX_TERMINAL_NOTE_LENGTH) + "\n[error truncated]";
            }
            sink.write("[task " + status + "]\n" + detail + "\n");
        } catch (RuntimeException e) {
            LOGGER.warn("failed to write terminal note for background task: {}", e.getMessage());
        }
    }

    private BackgroundTaskRunner() {
    }
}
