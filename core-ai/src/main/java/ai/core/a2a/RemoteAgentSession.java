package ai.core.a2a;

import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.Part;
import ai.core.api.a2a.SendMessageConfiguration;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.a2a.TaskStatusUpdateEvent;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.ReasoningChunkEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentSession implementation backed by a remote A2A agent.
 *
 * @author xander
 */
public class RemoteAgentSession implements AgentSession {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteAgentSession.class);

    public static RemoteAgentSession connect(A2AClient client) {
        return new RemoteAgentSession("a2a-" + UUID.randomUUID(), client);
    }

    private final String sessionId;
    private final A2AClient client;
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private volatile String currentTaskId;
    private volatile String contextId;
    private volatile boolean closed;

    public RemoteAgentSession(String sessionId, A2AClient client) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required");
        }
        if (client == null) {
            throw new IllegalArgumentException("client required");
        }
        this.sessionId = sessionId;
        this.client = client;
    }

    @Override
    public String id() {
        return sessionId;
    }

    @Override
    public void sendMessage(String message) {
        sendMessage(message, null);
    }

    @Override
    public void sendMessage(String message, Map<String, Object> variables) {
        if (closed) throw new IllegalStateException("remote agent session closed");
        var request = new SendMessageRequest();
        request.message = Message.user(message != null ? message : "");
        request.message.contextId = contextId;
        if (variables != null && !variables.isEmpty()) {
            request.metadata = variables;
        }
        var config = new SendMessageConfiguration();
        config.acceptedOutputModes = List.of("text/plain", "application/json");
        request.configuration = config;
        streamAndWait(request);
    }

    @Override
    public void onEvent(AgentEventListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeEvent(AgentEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void approveToolCall(String callId, ApprovalDecision decision) {
        if (closed) return;
        if (currentTaskId == null || currentTaskId.isBlank()) {
            dispatch(ErrorEvent.of(sessionId, "remote task is not awaiting approval", null));
            return;
        }
        var request = new SendMessageRequest();
        var message = new Message();
        message.role = "ROLE_USER";
        message.taskId = currentTaskId;
        message.contextId = contextId;
        message.parts = List.of(Part.data(Map.of(
                "call_id", callId,
                "decision", decision.name().startsWith("DENY") ? "deny" : "approve"
        )));
        request.message = message;
        streamAndWait(request);
    }

    @Override
    public void cancelTurn() {
        var taskId = currentTaskId;
        if (taskId == null || taskId.isBlank()) return;
        try {
            var request = new CancelTaskRequest();
            request.id = taskId;
            client.cancelTask(request);
        } catch (Exception e) {
            LOGGER.debug("failed to cancel remote A2A task, sessionId={}, taskId={}", sessionId, taskId, e);
        }
        dispatch(TurnCompleteEvent.cancelled(sessionId));
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
    }

    @Override
    public void close() {
        closed = true;
        try {
            client.close();
        } catch (Exception e) {
            LOGGER.debug("failed to close remote A2A client, sessionId={}", sessionId, e);
        }
    }

    private void streamAndWait(SendMessageRequest request) {
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var turn = new TurnState();
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.RUNNING));
        try {
            client.stream(request).subscribe(new StreamSubscriber(turn, done, failure));
            waitForStream(done);
        } catch (Exception e) {
            failure.set(e);
            dispatch(ErrorEvent.of(sessionId, e.getMessage(), null));
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.ERROR));
        }
        if (failure.get() != null) {
            LOGGER.debug("remote A2A stream failed, sessionId={}", sessionId, failure.get());
        }
    }

    private void waitForStream(CountDownLatch done) {
        try {
            while (!done.await(1, TimeUnit.MINUTES)) {
                if (closed) return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dispatch(TurnCompleteEvent.cancelled(sessionId));
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
        }
    }

    private void handleStreamEvent(A2AStreamEvent event, TurnState turn) {
        if (event == null) return;
        if (event.error != null) {
            dispatch(ErrorEvent.of(sessionId, event.error.message, null));
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.ERROR));
            return;
        }
        if (event.task != null) handleTask(event.task, turn);
        if (event.message != null) handleMessage(event.message, turn);
        if (event.artifactUpdate != null && event.artifactUpdate.artifact != null) {
            appendArtifact(event.artifactUpdate.artifact, turn);
        }
        if (event.statusUpdate != null) handleStatusUpdate(event.statusUpdate, turn);
    }

    private void handleTask(Task task, TurnState turn) {
        currentTaskId = task.id != null ? task.id : currentTaskId;
        contextId = task.contextId != null ? task.contextId : contextId;
        if (task.artifacts != null) {
            for (var artifact : task.artifacts) {
                appendArtifact(artifact, turn);
            }
        }
        if (task.status != null) {
            handleStatus(task.status, task.metadata, turn);
        }
    }

    private void handleMessage(Message message, TurnState turn) {
        currentTaskId = message.taskId != null ? message.taskId : currentTaskId;
        contextId = message.contextId != null ? message.contextId : contextId;
        var text = message.extractText();
        if (text != null && !text.isBlank()) {
            turn.output.append(text);
            dispatch(TextChunkEvent.of(sessionId, text));
        }
        completeTurn(turn, false);
    }

    private void appendArtifact(Artifact artifact, TurnState turn) {
        if (artifact.parts == null) return;
        for (var part : artifact.parts) {
            var text = text(part);
            if (text == null || text.isEmpty()) continue;
            turn.output.append(text);
            dispatch(TextChunkEvent.of(sessionId, text));
        }
    }

    private String text(Part part) {
        if (part == null || part.text == null) return null;
        return part.text;
    }

    private void handleStatusUpdate(TaskStatusUpdateEvent event, TurnState turn) {
        currentTaskId = event.taskId != null ? event.taskId : currentTaskId;
        contextId = event.contextId != null ? event.contextId : contextId;
        handleMetadata(event.metadata);
        if (event.status != null) {
            handleStatus(event.status, event.metadata, turn);
        }
    }

    private void handleMetadata(Map<String, Object> metadata) {
        if (metadata == null) return;
        var event = stringValue(metadata, "event");
        if ("reasoning".equals(event)) {
            dispatch(ReasoningChunkEvent.of(sessionId, stringValue(metadata, "chunk")));
        } else if ("tool_start".equals(event)) {
            dispatch(ToolStartEvent.of(sessionId, callId(metadata), stringValue(metadata, "tool"), stringValue(metadata, "arguments")));
        } else if ("tool_result".equals(event)) {
            dispatch(ToolResultEvent.of(sessionId, callId(metadata), stringValue(metadata, "tool"),
                    stringValue(metadata, "result_status"), stringValue(metadata, "result")));
        }
    }

    private String callId(Map<String, Object> metadata) {
        var value = stringValue(metadata, "call_id");
        if (value != null) return value;
        return stringValue(metadata, "callId");
    }

    private void handleStatus(TaskStatus status, Map<String, Object> metadata, TurnState turn) {
        if (status.state == null) return;
        switch (status.state) {
            case COMPLETED -> completeTurn(turn, false);
            case CANCELED -> completeTurn(turn, true);
            case FAILED, REJECTED, AUTH_REQUIRED -> failTurn(status);
            case INPUT_REQUIRED -> requestApproval(status, metadata);
            case SUBMITTED, WORKING, UNKNOWN -> {
                // non-terminal progress state
            }
            default -> {
            }
        }
    }

    private void completeTurn(TurnState turn, boolean cancelled) {
        if (turn.completed) return;
        turn.completed = true;
        var event = cancelled
                ? TurnCompleteEvent.cancelled(sessionId)
                : TurnCompleteEvent.of(sessionId, turn.output.toString());
        dispatch(event);
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.IDLE));
    }

    private void failTurn(TaskStatus status) {
        var message = status.message != null ? status.message.extractText() : null;
        if (message == null || message.isBlank()) {
            message = "remote A2A task failed: " + status.state;
        }
        dispatch(ErrorEvent.of(sessionId, message, null));
        dispatch(StatusChangeEvent.of(sessionId, SessionStatus.ERROR));
    }

    private void requestApproval(TaskStatus status, Map<String, Object> metadata) {
        var message = status.message;
        var approvalMetadata = metadata;
        if ((approvalMetadata == null || approvalMetadata.isEmpty()) && message != null) {
            approvalMetadata = message.metadata;
        }
        var callId = approvalMetadata != null ? callId(approvalMetadata) : null;
        var toolName = approvalMetadata != null ? stringValue(approvalMetadata, "tool") : null;
        var arguments = approvalMetadata != null ? stringValue(approvalMetadata, "arguments") : null;
        if (callId == null && currentTaskId != null) {
            callId = currentTaskId;
        }
        if (toolName == null) {
            toolName = "remote_tool";
        }
        dispatch(ToolApprovalRequestEvent.of(sessionId, callId, toolName, arguments, null));
    }

    private String stringValue(Map<String, Object> metadata, String key) {
        var value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private void dispatch(AgentEvent event) {
        for (var listener : listeners) {
            try {
                switch (event) {
                    case TextChunkEvent e -> listener.onTextChunk(e);
                    case ReasoningChunkEvent e -> listener.onReasoningChunk(e);
                    case ToolStartEvent e -> listener.onToolStart(e);
                    case ToolResultEvent e -> listener.onToolResult(e);
                    case ToolApprovalRequestEvent e -> listener.onToolApprovalRequest(e);
                    case TurnCompleteEvent e -> listener.onTurnComplete(e);
                    case ErrorEvent e -> listener.onError(e);
                    case StatusChangeEvent e -> listener.onStatusChange(e);
                    default -> {
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("failed to dispatch remote A2A event, sessionId={}, event={}",
                        sessionId, event.getClass().getSimpleName(), e);
            }
        }
    }

    private static final class TurnState {
        final StringBuilder output = new StringBuilder();
        boolean completed;
    }

    private final class StreamSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        private final TurnState turn;
        private final CountDownLatch done;
        private final AtomicReference<Throwable> failure;

        private StreamSubscriber(TurnState turn, CountDownLatch done, AtomicReference<Throwable> failure) {
            this.turn = turn;
            this.done = done;
            this.failure = failure;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(A2AStreamEvent item) {
            handleStreamEvent(item, turn);
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
            dispatch(ErrorEvent.of(sessionId, throwable.getMessage(), null));
            dispatch(StatusChangeEvent.of(sessionId, SessionStatus.ERROR));
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }
    }
}
