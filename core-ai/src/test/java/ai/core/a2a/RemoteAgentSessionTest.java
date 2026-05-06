package ai.core.a2a;

import ai.core.api.a2a.AgentCard;
import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.a2a.TaskStatusUpdateEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteAgentSessionTest {
    @Test
    void sendMessageConvertsA2AStreamToAgentEvents() {
        var client = new FakeA2AClient();
        client.streams.add(List.of(
                A2AStreamEvent.ofTask(task("task-1", "ctx-1", TaskState.WORKING)),
                A2AStreamEvent.ofArtifactUpdate(artifact("task-1", "ctx-1", "hello ")),
                A2AStreamEvent.ofArtifactUpdate(artifact("task-1", "ctx-1", "world")),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.COMPLETED, null))
        ));
        var session = new RemoteAgentSession("remote-1", client);
        var listener = new RecordingListener();
        session.onEvent(listener);

        session.sendMessage("hi");

        assertEquals("hi", client.requests.getFirst().message.extractText());
        assertEquals("hello world", listener.text());
        assertTrue(listener.events.stream().anyMatch(e -> e instanceof StatusChangeEvent s && s.status == SessionStatus.RUNNING));
        assertTrue(listener.events.stream().anyMatch(e -> e instanceof TurnCompleteEvent));
    }

    @Test
    void sendMessageReusesA2AContextId() {
        var client = new FakeA2AClient();
        client.streams.add(List.of(
                A2AStreamEvent.ofTask(task("task-1", "ctx-1", TaskState.WORKING)),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.COMPLETED, null))
        ));
        client.streams.add(List.of(
                A2AStreamEvent.ofTask(task("task-2", "ctx-1", TaskState.WORKING)),
                A2AStreamEvent.ofStatusUpdate(status("task-2", "ctx-1", TaskState.COMPLETED, null))
        ));
        var session = new RemoteAgentSession("remote-1", client);
        session.onEvent(new RecordingListener());

        session.sendMessage("first");
        session.sendMessage("second");

        assertEquals("ctx-1", client.requests.get(1).message.contextId);
    }

    @Test
    void toolApprovalContinuesInputRequiredTask() {
        var client = new FakeA2AClient();
        client.streams.add(List.of(
                A2AStreamEvent.ofTask(task("task-1", "ctx-1", TaskState.WORKING)),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.INPUT_REQUIRED,
                        Map.of("event", "approval", "call_id", "call-1", "tool", "shell", "arguments", "{}")))
        ));
        client.streams.add(List.of(
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.WORKING,
                        Map.of("event", "tool_start", "call_id", "call-1", "tool", "shell", "arguments", "{}"))),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.WORKING,
                        Map.of("event", "tool_result", "call_id", "call-1", "tool", "shell", "result_status", "success", "result", "ok"))),
                A2AStreamEvent.ofArtifactUpdate(artifact("task-1", "ctx-1", "done")),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.COMPLETED, null))
        ));
        var session = new RemoteAgentSession("remote-1", client);
        var listener = new RecordingListener() {
            @Override
            public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
                super.onToolApprovalRequest(event);
                session.approveToolCall(event.callId, ApprovalDecision.APPROVE);
            }
        };
        session.onEvent(listener);

        session.sendMessage("run shell");

        assertEquals(2, client.requests.size());
        assertEquals("task-1", client.requests.get(1).message.taskId);
        assertInstanceOf(Map.class, client.requests.get(1).message.parts.getFirst().data);
        assertEquals("done", listener.text());
        assertTrue(listener.events.stream().anyMatch(e -> e instanceof ToolApprovalRequestEvent t && "call-1".equals(t.callId)));
        assertTrue(listener.events.stream().anyMatch(e -> e instanceof ToolStartEvent t && "shell".equals(t.toolName)));
        assertTrue(listener.events.stream().anyMatch(e -> e instanceof ToolResultEvent t && "ok".equals(t.result)));
        assertTrue(listener.events.stream().anyMatch(e -> e instanceof TurnCompleteEvent));
    }

    private Task task(String taskId, String contextId, TaskState state) {
        var task = new Task();
        task.id = taskId;
        task.contextId = contextId;
        task.status = TaskStatus.of(state);
        return task;
    }

    private TaskStatusUpdateEvent status(String taskId, String contextId, TaskState state, Map<String, Object> metadata) {
        var event = new TaskStatusUpdateEvent();
        event.taskId = taskId;
        event.contextId = contextId;
        event.status = TaskStatus.of(state);
        event.metadata = metadata;
        return event;
    }

    private ai.core.api.a2a.TaskArtifactUpdateEvent artifact(String taskId, String contextId, String text) {
        var event = new ai.core.api.a2a.TaskArtifactUpdateEvent();
        event.taskId = taskId;
        event.contextId = contextId;
        event.artifact = Artifact.text(text);
        return event;
    }

    private static final class FakeA2AClient implements A2AClient {
        final Queue<List<A2AStreamEvent>> streams = new ArrayDeque<>();
        final List<SendMessageRequest> requests = new ArrayList<>();

        @Override
        public AgentCard getAgentCard() {
            return new AgentCard();
        }

        @Override
        public A2AInvocationResult send(SendMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<A2AStreamEvent> stream(SendMessageRequest request) {
            requests.add(request);
            var events = streams.remove();
            return subscriber -> subscriber.onSubscribe(new EventSubscription(subscriber, events));
        }

        @Override
        public Task getTask(GetTaskRequest request) {
            return null;
        }

        @Override
        public Task cancelTask(CancelTaskRequest request) {
            return null;
        }
    }

    private static final class EventSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super A2AStreamEvent> subscriber;
        private final List<A2AStreamEvent> events;
        private boolean sent;

        private EventSubscription(Flow.Subscriber<? super A2AStreamEvent> subscriber, List<A2AStreamEvent> events) {
            this.subscriber = subscriber;
            this.events = events;
        }

        @Override
        public void request(long n) {
            if (sent) return;
            sent = true;
            for (var event : events) {
                subscriber.onNext(event);
            }
            subscriber.onComplete();
        }

        @Override
        public void cancel() {
            sent = true;
        }
    }

    private static class RecordingListener implements AgentEventListener {
        final List<Object> events = new ArrayList<>();

        @Override
        public void onTextChunk(TextChunkEvent event) {
            events.add(event);
        }

        @Override
        public void onToolStart(ToolStartEvent event) {
            events.add(event);
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
            events.add(event);
        }

        @Override
        public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
            events.add(event);
        }

        @Override
        public void onTurnComplete(TurnCompleteEvent event) {
            events.add(event);
        }

        @Override
        public void onStatusChange(StatusChangeEvent event) {
            events.add(event);
        }

        String text() {
            var sb = new StringBuilder();
            for (var event : events) {
                if (event instanceof TextChunkEvent text) {
                    sb.append(text.chunk);
                }
            }
            return sb.toString();
        }
    }
}
