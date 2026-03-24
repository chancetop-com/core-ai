package ai.core.a2a;

import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class A2AEventAdapter implements AgentEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2AEventAdapter.class);

    private final String taskId;
    private final Consumer<String> sseSender;
    private final A2ATaskState taskState;
    private final CompletableFuture<Task> syncFuture;

    public A2AEventAdapter(String taskId, A2ATaskState taskState, Consumer<String> sseSender, CompletableFuture<Task> syncFuture) {
        this.taskId = taskId;
        this.taskState = taskState;
        this.sseSender = sseSender;
        this.syncFuture = syncFuture;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        taskState.appendOutput(event.chunk);
        sendSseEvent(statusEvent("working", Message.agent(event.chunk)));
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        var data = new HashMap<String, Object>();
        data.put("type", "status");
        data.put("taskId", taskId);
        data.put("status", Map.of("state", "working"));
        data.put("metadata", Map.of("event", "reasoning", "chunk", event.chunk));
        sendSseEvent(data);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        var data = new HashMap<String, Object>();
        data.put("type", "status");
        data.put("taskId", taskId);
        data.put("status", Map.of("state", "working"));
        data.put("metadata", Map.of("event", "tool_start", "call_id", event.callId, "tool", event.toolName, "arguments", event.arguments != null ? event.arguments : ""));
        sendSseEvent(data);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        var data = new HashMap<String, Object>();
        data.put("type", "status");
        data.put("taskId", taskId);
        data.put("status", Map.of("state", "working"));
        data.put("metadata", Map.of("event", "tool_result", "call_id", event.callId, "tool", event.toolName, "result_status", event.status, "result", event.result != null ? event.result : ""));
        sendSseEvent(data);
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        taskState.setState(TaskState.INPUT_REQUIRED);
        taskState.setAwait(event.callId, event.toolName, event.arguments);
        var msg = Message.agent("Tool requires approval: " + event.toolName);
        var data = new HashMap<String, Object>();
        data.put("type", "status");
        data.put("taskId", taskId);
        data.put("status", Map.of("state", "input-required", "message", msg));
        data.put("metadata", Map.of("call_id", event.callId, "tool", event.toolName, "arguments", event.arguments != null ? event.arguments : ""));
        sendSseEvent(data);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        if (Boolean.TRUE.equals(event.cancelled)) {
            taskState.setState(TaskState.CANCELED);
        } else {
            taskState.setState(TaskState.COMPLETED);
        }
        taskState.clearAwait();

        if (event.inputTokens != null) {
            taskState.inputTokens = event.inputTokens;
            taskState.outputTokens = event.outputTokens;
        }

        var fullOutput = taskState.getFullOutput();
        if (!fullOutput.isEmpty()) {
            sendSseEvent(artifactEvent(fullOutput));
        }
        sendSseEvent(statusEvent(Boolean.TRUE.equals(event.cancelled) ? "canceled" : "completed", null));

        if (syncFuture != null) {
            syncFuture.complete(taskState.toTask());
        }
    }

    @Override
    public void onError(ErrorEvent event) {
        taskState.setState(TaskState.FAILED);
        taskState.clearAwait();
        taskState.errorMessage = event.message;

        sendSseEvent(statusEvent("failed", Message.agent(event.message)));

        if (syncFuture != null) {
            syncFuture.complete(taskState.toTask());
        }
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        // status changes reflected via other events
    }

    private Map<String, Object> statusEvent(String state, Message message) {
        var data = new HashMap<String, Object>();
        data.put("type", "status");
        data.put("taskId", taskId);
        var status = new HashMap<String, Object>();
        status.put("state", state);
        if (message != null) status.put("message", message);
        data.put("status", status);
        return data;
    }

    private Map<String, Object> artifactEvent(String text) {
        var data = new HashMap<String, Object>();
        data.put("type", "artifact");
        data.put("taskId", taskId);
        data.put("artifact", Artifact.text(text));
        return data;
    }

    private void sendSseEvent(Map<String, Object> data) {
        if (sseSender == null) return;
        try {
            sseSender.accept(JsonUtil.toJson(data));
        } catch (Exception e) {
            LOGGER.debug("failed to send SSE event, taskId={}", taskId, e);
        }
    }
}
