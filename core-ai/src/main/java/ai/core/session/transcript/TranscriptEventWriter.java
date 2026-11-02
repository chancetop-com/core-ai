package ai.core.session.transcript;

import ai.core.api.server.session.BatchToolStartEvent;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.CustomEvent;
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.SandboxEvent;
import ai.core.api.server.session.TaskStatusEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Folds streaming events into transcript records. Every method is called with the recorder lock held.
 *
 * @author stephen
 */
final class TranscriptEventWriter {
    private static final int MAX_ENVIRONMENT_TAIL = 32 * 1024;

    private final TranscriptRecorder recorder;
    private final StringBuilder text = new StringBuilder();
    private final Map<String, StringBuilder> environment = new HashMap<>();

    TranscriptEventWriter(TranscriptRecorder recorder) {
        this.recorder = recorder;
    }

    void flushLocked() {
        if (text.isEmpty()) return;
        var record = recorder.writeLocked(TranscriptKinds.ASSISTANT_TEXT, recorder.currentTurnId());
        TranscriptPayloads.putText(recorder.store(), record, "text", text.toString());
        recorder.commitLocked(record);
        text.setLength(0);
    }

    void onTextChunkLocked(TextChunkEvent event) {
        if (recorder.currentTurnId() == null) return;
        text.append(event.chunk);
    }

    void onReasoningCompleteLocked(ReasoningCompleteEvent event) {
        flushLocked();
        if (recorder.currentTurnId() == null) return;
        var record = recorder.writeLocked(TranscriptKinds.REASONING, recorder.currentTurnId());
        TranscriptPayloads.putText(recorder.store(), record, "reasoning", event.reasoning);
        record.put("durationMs", event.durationMs);
        recorder.commitLocked(record);
    }

    void onToolStartLocked(ToolStartEvent event) {
        flushLocked();
        if (recorder.currentTurnId() == null) return;
        var record = recorder.writeLocked(TranscriptKinds.TOOL_START, recorder.currentTurnId());
        record.put("callId", event.callId);
        record.put("toolName", event.toolName);
        TranscriptPayloads.putText(recorder.store(), record, "arguments", event.arguments);
        TranscriptPayloads.putText(recorder.store(), record, "diff", event.diff);
        record.put("taskId", event.taskId);
        record.put("run_in_background", event.runInBackground);
        record.put("model", event.model);
        record.put("toolType", event.toolType);
        recorder.commitLocked(record);
    }

    void onToolResultLocked(ToolResultEvent event) {
        flushLocked();
        if (recorder.currentTurnId() == null) return;
        var tail = environment.remove(event.callId);
        var record = recorder.writeLocked(TranscriptKinds.TOOL_RESULT, recorder.currentTurnId());
        record.put("callId", event.callId);
        record.put("toolName", event.toolName);
        record.put("status", event.status);
        TranscriptPayloads.putText(recorder.store(), record, "result", mergeResult(event.result, tail));
        record.put("durationMs", event.durationMs);
        record.put("taskId", event.taskId);
        record.put("toolType", event.toolType);
        recorder.commitLocked(record);
    }

    void onToolApprovalRequestLocked(ToolApprovalRequestEvent event) {
        flushLocked();
        if (recorder.currentTurnId() == null) return;
        var record = recorder.writeLocked(TranscriptKinds.TOOL_APPROVAL, recorder.currentTurnId());
        record.put("callId", event.callId);
        record.put("toolName", event.toolName);
        record.put("suggestedPattern", event.suggestedPattern);
        recorder.commitLocked(record);
    }

    void onPlanUpdateLocked(PlanUpdateEvent event) {
        flushLocked();
        if (recorder.currentTurnId() == null) return;
        var record = recorder.writeLocked(TranscriptKinds.PLAN_UPDATE, recorder.currentTurnId());
        record.put("todos", event.todos);
        recorder.commitLocked(record);
    }

    void onCompressionLocked(CompressionEvent event) {
        var record = recorder.writeLocked(TranscriptKinds.COMPRESSION, recorder.currentTurnId());
        record.put("before_count", event.beforeCount);
        record.put("after_count", event.afterCount);
        record.put("completed", event.completed);
        record.put("context_tokens", event.contextTokens);
        record.put("max_context_tokens", event.maxContextTokens);
        record.put("trigger_threshold", event.triggerThreshold);
        recorder.commitLocked(record);
    }

    void onSandboxLocked(SandboxEvent event) {
        var record = recorder.writeLocked(TranscriptKinds.SANDBOX, recorder.currentTurnId());
        record.put("sandboxId", event.sandboxId);
        record.put("type", event.type);
        record.put("message", event.message);
        record.put("durationMs", event.durationMs);
        record.put("hostname", event.hostname);
        record.put("ip", event.ip);
        record.put("image", event.image);
        recorder.commitLocked(record);
    }

    void onBatchToolStartLocked(BatchToolStartEvent event) {
        flushLocked();
        if (recorder.currentTurnId() == null) return;
        var record = recorder.writeLocked(TranscriptKinds.BATCH_TOOL_START, recorder.currentTurnId());
        record.put("group", event.group());
        record.put("tools", event.tools());
        record.put("task_id", event.taskId());
        recorder.commitLocked(record);
    }

    void onTaskStatusLocked(TaskStatusEvent event) {
        var record = recorder.writeLocked(TranscriptKinds.TASK_STATUS, null);
        record.put("taskId", event.taskId);
        record.put("toolName", event.toolName);
        record.put("status", event.status);
        recorder.commitLocked(record);
    }

    void onCustomEventLocked(CustomEvent event) {
        if (isBubbleSealing(event)) flushLocked();
        var record = recorder.writeLocked(TranscriptKinds.CUSTOM, recorder.currentTurnId());
        record.put("name", event.name);
        TranscriptPayloads.putText(recorder.store(), record, "data", event.data);
        TranscriptPayloads.putText(recorder.store(), record, "card", event.card);
        record.put("callId", event.callId);
        recorder.commitLocked(record);
    }

    void onEnvironmentOutputLocked(EnvironmentOutputChunkEvent event) {
        if (recorder.currentTurnId() == null || event.callId == null) return;
        var tail = environment.computeIfAbsent(event.callId, key -> new StringBuilder());
        tail.append(event.chunk == null ? "" : event.chunk);
        if (tail.length() > MAX_ENVIRONMENT_TAIL) {
            tail.delete(0, tail.length() - MAX_ENVIRONMENT_TAIL);
        }
    }

    void onErrorLocked(ErrorEvent event) {
        if (event == recorder.terminalError()) {
            recorder.clearTerminalError();
            return;
        }
        var record = recorder.writeLocked(TranscriptKinds.ERROR, recorder.currentTurnId());
        record.put("terminal", false);
        record.put("message", event.message);
        record.put("detail", event.detail);
        recorder.commitLocked(record);
    }

    private static boolean isBubbleSealing(CustomEvent event) {
        if (event.card != null) return true;
        return "quick_replies".equals(event.name);
    }

    private static String mergeResult(String result, StringBuilder tail) {
        if (tail == null || tail.isEmpty()) return result;
        if (result == null || result.isBlank()) return tail.toString();
        if (result.contains(tail)) return result;
        return result + "\n" + tail;
    }
}
