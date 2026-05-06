package ai.core.a2a;

import ai.core.api.a2a.Part;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.AgentSession;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2AEventAdapterTest {
    @Test
    void toolApprovalCompletesBlockingFutureAndKeepsTaskListener() {
        var session = new FakeSession();
        var state = new A2ATaskState("task-1", "ctx-1", session);
        var future = new CompletableFuture<Task>();
        var adapter = new A2AEventAdapter("task-1", state, null, future);
        state.attachEventListener(adapter);

        adapter.onToolApprovalRequest(ToolApprovalRequestEvent.of("ctx-1", "call-1", "shell", "{}", null));

        assertTrue(future.isDone());
        assertEquals(TaskState.INPUT_REQUIRED, future.join().status.state);
        assertEquals("call-1", state.getAwaitCallId());
        assertTrue(session.listeners.contains(adapter), "input-required task must keep its state listener for resume");
    }

    @Test
    void terminalCompletionDetachesTaskListener() {
        var session = new FakeSession();
        var state = new A2ATaskState("task-1", "ctx-1", session);
        var future = new CompletableFuture<Task>();
        var adapter = new A2AEventAdapter("task-1", state, null, future);
        state.attachEventListener(adapter);

        adapter.onTurnComplete(TurnCompleteEvent.of("ctx-1", "done"));

        assertTrue(future.isDone());
        assertEquals(TaskState.COMPLETED, future.join().status.state);
        assertFalse(session.listeners.contains(adapter), "terminal task listener should be removed");
    }

    @Test
    void streamedArtifactChunksHaveCorrectAppendAndLastChunkFlags() {
        var session = new FakeSession();
        var state = new A2ATaskState("task-1", "ctx-1", session);
        var events = new ArrayList<String>();
        var adapter = new A2AEventAdapter("task-1", state, events::add, null);
        state.attachEventListener(adapter);
        state.setStreamCloser(adapter::stopStreaming);

        adapter.onTextChunk(TextChunkEvent.of("ctx-1", "hello "));
        assertTrue(events.isEmpty(), "adapter delays one chunk so the last content chunk can be marked lastChunk=true");

        adapter.onTextChunk(TextChunkEvent.of("ctx-1", "world"));
        assertEquals(1, events.size());
        var first = JsonUtil.fromJson(StreamResponse.class, events.getFirst());
        assertEquals("hello ", first.artifactUpdate.artifact.parts.getFirst().text);
        assertEquals(false, first.artifactUpdate.append);
        assertEquals(false, first.artifactUpdate.lastChunk);

        adapter.onTurnComplete(TurnCompleteEvent.of("ctx-1", "hello world"));

        assertEquals(3, events.size());
        var second = JsonUtil.fromJson(StreamResponse.class, events.get(1));
        assertEquals("world", second.artifactUpdate.artifact.parts.getFirst().text);
        assertEquals(true, second.artifactUpdate.append);
        assertEquals(true, second.artifactUpdate.lastChunk);
        assertTrue(events.get(2).contains("\"statusUpdate\""));
        assertTrue(events.get(2).contains("\"TASK_STATE_COMPLETED\""));
    }

    @Test
    void stopStreamingDoesNotDetachStateListener() {
        var session = new FakeSession();
        var state = new A2ATaskState("task-1", "ctx-1", session);
        var events = new ArrayList<String>();
        var adapter = new A2AEventAdapter("task-1", state, events::add, null);
        state.attachEventListener(adapter);

        adapter.stopStreaming();
        adapter.onTextChunk(TextChunkEvent.of("ctx-1", "hidden"));
        adapter.onToolApprovalRequest(ToolApprovalRequestEvent.of("ctx-1", "call-1", "shell", "{}", null));

        assertTrue(events.isEmpty());
        assertEquals(TaskState.INPUT_REQUIRED, state.getState());
        assertTrue(session.listeners.contains(adapter), "closing an SSE stream should not detach task state tracking");
    }

    @Test
    void partDataAcceptsAnyJsonValue() {
        var list = List.of("a", "b");
        var part = Part.data(list);

        assertSame(list, part.data);
        assertInstanceOf(List.class, part.data);
    }

    private static final class FakeSession implements AgentSession {
        final List<AgentEventListener> listeners = new ArrayList<>();

        @Override
        public String id() {
            return "ctx-1";
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void sendMessage(String message, Map<String, Object> variables) {
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
        }

        @Override
        public void cancelTurn() {
        }

        @Override
        public void close() {
        }
    }
}
