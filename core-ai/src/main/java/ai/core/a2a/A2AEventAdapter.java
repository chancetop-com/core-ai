package ai.core.a2a;

import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskArtifactUpdateEvent;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.a2a.TaskStatusUpdateEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class A2AEventAdapter implements AgentEventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2AEventAdapter.class);

    private final String taskId;
    private final A2ATaskState taskState;
    private final CompletableFuture<Task> syncFuture;
    private volatile Consumer<StreamResponse> streamSender;
    private String pendingTextChunk;
    private boolean artifactStarted;

    public A2AEventAdapter(String taskId, A2ATaskState taskState, Consumer<StreamResponse> streamSender, CompletableFuture<Task> syncFuture) {
        this.taskId = taskId;
        this.taskState = taskState;
        this.streamSender = streamSender;
        this.syncFuture = syncFuture;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        taskState.appendOutput(event.chunk);
        if (pendingTextChunk != null) {
            sendSseEvent(artifactEvent(pendingTextChunk, artifactStarted, false));
            artifactStarted = true;
        }
        pendingTextChunk = event.chunk;
    }

    @Override
    public void onReasoningChunk(ReasoningChunkEvent event) {
        sendSseEvent(statusEvent(TaskState.WORKING, null, Map.of("event", "reasoning", "chunk", event.chunk)));
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        sendSseEvent(statusEvent(TaskState.WORKING, null, Map.of("event", "tool_start", "call_id", event.callId, "tool", event.toolName, "arguments", event.arguments != null ? event.arguments : "")));
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        sendSseEvent(statusEvent(TaskState.WORKING, null, Map.of("event", "tool_result", "call_id", event.callId, "tool", event.toolName, "result_status", event.status, "result", event.result != null ? event.result : "")));
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        flushPendingArtifact(false);
        taskState.setAwait(event.callId, event.toolName, event.arguments);
        var msg = Message.agent("Tool requires approval: " + event.toolName);
        sendSseEvent(statusEvent(TaskState.INPUT_REQUIRED, msg, Map.of("call_id", event.callId, "tool", event.toolName, "arguments", event.arguments != null ? event.arguments : "")));
        taskState.setState(TaskState.INPUT_REQUIRED);
        taskState.closeStream();
        completeSyncFuture();
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        flushPendingArtifact(true);
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

        sendSseEvent(statusEvent(Boolean.TRUE.equals(event.cancelled) ? TaskState.CANCELED : TaskState.COMPLETED, null, null));
        taskState.detachEventListener();

        completeSyncFuture();
    }

    @Override
    public void onError(ErrorEvent event) {
        flushPendingArtifact(true);
        taskState.setState(TaskState.FAILED);
        taskState.clearAwait();
        taskState.errorMessage = event.message;

        sendSseEvent(statusEvent(TaskState.FAILED, Message.agent(event.message), null));
        taskState.detachEventListener();

        completeSyncFuture();
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        // status changes reflected via other events
    }

    private StreamResponse statusEvent(TaskState state, Message message, Map<String, Object> metadata) {
        var event = new TaskStatusUpdateEvent();
        event.taskId = taskId;
        event.contextId = taskState.contextId;
        event.status = TaskStatus.of(state, message);
        event.metadata = metadata;
        return StreamResponse.ofStatusUpdate(event);
    }

    private StreamResponse artifactEvent(String text, boolean append, boolean lastChunk) {
        var event = new TaskArtifactUpdateEvent();
        event.taskId = taskId;
        event.contextId = taskState.contextId;
        event.artifact = Artifact.text(text);
        event.append = append;
        event.lastChunk = lastChunk;
        return StreamResponse.ofArtifactUpdate(event);
    }

    private void flushPendingArtifact(boolean lastChunk) {
        if (pendingTextChunk == null) return;
        sendSseEvent(artifactEvent(pendingTextChunk, artifactStarted, lastChunk));
        artifactStarted = true;
        pendingTextChunk = null;
    }

    private void completeSyncFuture() {
        if (syncFuture != null) {
            syncFuture.complete(taskState.toTask());
        }
    }

    public void stopStreaming() {
        streamSender = null;
    }

    public void startStreaming(Consumer<StreamResponse> streamSender) {
        this.streamSender = streamSender;
    }

    private void sendSseEvent(StreamResponse data) {
        var sender = streamSender;
        if (sender == null) return;
        try {
            sender.accept(data);
        } catch (Exception e) {
            LOGGER.debug("failed to send SSE event, taskId={}", taskId, e);
        }
    }
}
