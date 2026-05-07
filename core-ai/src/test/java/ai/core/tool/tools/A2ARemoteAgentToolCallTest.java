package ai.core.tool.tools;

import ai.core.a2a.A2AInvocationResult;
import ai.core.a2a.A2ARemoteAgentDescriptor;
import ai.core.a2a.A2AStreamEvent;
import ai.core.a2a.InMemoryRemoteAgentContextStore;
import ai.core.a2a.RemoteAgentClient;
import ai.core.agent.ExecutionContext;
import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskArtifactUpdateEvent;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.a2a.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2ARemoteAgentToolCallTest {
    @Test
    void streamCallSavesAndReusesRemoteContext() {
        var client = new FakeClient();
        client.streams.add(List.of(
                A2AStreamEvent.ofTask(task("task-1", "ctx-1", TaskState.WORKING)),
                A2AStreamEvent.ofArtifactUpdate(artifact("task-1", "ctx-1", "server output")),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.COMPLETED, null))
        ));
        client.streams.add(List.of(
                A2AStreamEvent.ofArtifactUpdate(artifact("task-2", "ctx-1", "second output")),
                A2AStreamEvent.ofStatusUpdate(status("task-2", "ctx-1", TaskState.COMPLETED, null))
        ));
        var store = new InMemoryRemoteAgentContextStore();
        var tool = tool(client, store);
        var context = ExecutionContext.builder().sessionId("local-1").build();

        var first = tool.execute("{\"query\":\"first\"}", context);
        var second = tool.execute("{\"query\":\"second\"}", context);

        assertTrue(first.isCompleted());
        assertEquals("server output", first.getResult());
        assertEquals("second output", second.getResult());
        assertNull(client.requests.getFirst().message.contextId);
        assertEquals("ctx-1", client.requests.get(1).message.contextId);
        assertEquals("ctx-1", store.get("local-1", "enterprise").orElseThrow().contextId);
    }

    @Test
    void missingQueryFailsBeforeCallingRemoteAgent() {
        var client = new FakeClient();
        var tool = tool(client, new InMemoryRemoteAgentContextStore());

        var result = tool.execute("{}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("query"));
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void streamTaskStatusMessageIsReturnedWhenNoArtifact() {
        var client = new FakeClient();
        client.streams.add(List.of(A2AStreamEvent.ofTask(task("task-1", "ctx-1", TaskState.COMPLETED, "task done"))));
        var tool = tool(client, new InMemoryRemoteAgentContextStore());

        var result = tool.execute("{\"query\":\"run\"}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isCompleted());
        assertEquals("task done", result.getResult());
    }

    @Test
    void nonTerminalStreamEndFails() {
        var client = new FakeClient();
        client.streams.add(List.of(A2AStreamEvent.ofTask(task("task-1", "ctx-1", TaskState.WORKING, "still running"))));
        var tool = tool(client, new InMemoryRemoteAgentContextStore());

        var result = tool.execute("{\"query\":\"run\"}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("before terminal state"));
    }

    @Test
    void inputRequiredReturnsCompletedPromptForLocalAgent() {
        var client = new FakeClient();
        client.streams.add(List.of(A2AStreamEvent.ofStatusUpdate(
                status("task-1", "ctx-1", TaskState.INPUT_REQUIRED, "Need approval"))));
        var tool = tool(client, new InMemoryRemoteAgentContextStore());

        var result = tool.execute("{\"query\":\"run\"}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("Remote agent requires input"));
        assertTrue(result.getResult().contains("Need approval"));
    }

    @Test
    void inputRequiredUsesStatusMessageInsteadOfPriorArtifact() {
        var client = new FakeClient();
        client.streams.add(List.of(
                A2AStreamEvent.ofArtifactUpdate(artifact("task-1", "ctx-1", "partial output")),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.INPUT_REQUIRED, "Need approval"))
        ));
        var tool = tool(client, new InMemoryRemoteAgentContextStore());

        var result = tool.execute("{\"query\":\"run\"}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("Need approval"));
        assertFalse(result.getResult().contains("partial output"));
    }

    @Test
    void authRequiredFails() {
        var client = new FakeClient();
        client.streams.add(List.of(A2AStreamEvent.ofStatusUpdate(
                status("task-1", "ctx-1", TaskState.AUTH_REQUIRED, "Login required"))));
        var tool = tool(client, new InMemoryRemoteAgentContextStore());

        var result = tool.execute("{\"query\":\"run\"}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("authentication"));
    }

    @Test
    void failedStatusUsesStatusMessageInsteadOfPriorArtifact() {
        var client = new FakeClient();
        client.streams.add(List.of(
                A2AStreamEvent.ofArtifactUpdate(artifact("task-1", "ctx-1", "partial output")),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.FAILED, "Real failure"))
        ));
        var tool = tool(client, new InMemoryRemoteAgentContextStore());

        var result = tool.execute("{\"query\":\"run\"}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("Real failure"));
        assertFalse(result.getResult().contains("partial output"));
    }

    @Test
    void outputIsTruncated() {
        var client = new FakeClient();
        client.streams.add(List.of(
                A2AStreamEvent.ofArtifactUpdate(artifact("task-1", "ctx-1", "1234567890")),
                A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.COMPLETED, null))
        ));
        var descriptor = descriptor();
        descriptor.maxOutputChars = 4;
        var tool = tool(client, new InMemoryRemoteAgentContextStore(), descriptor);

        var result = tool.execute("{\"query\":\"run\"}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isCompleted());
        assertEquals("1234\n[truncated]", result.getResult());
        assertEquals(true, result.getStats().get("remote_output_truncated"));
    }

    @Test
    void timeoutCancelsStreamSubscription() {
        var client = new FakeClient();
        client.streamDelayMs = 500;
        client.streams.add(List.of(A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.COMPLETED, "done"))));
        var descriptor = descriptor();
        descriptor.timeout = Duration.ofMillis(20);
        var tool = tool(client, new InMemoryRemoteAgentContextStore(), descriptor);

        var result = tool.execute("{\"query\":\"run\"}", ExecutionContext.builder().sessionId("local-1").build());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("timed out"));
        assertTrue(client.subscriptions.getFirst().cancelled);
    }

    @Test
    void serializesCallsForSameRemoteContext() throws InterruptedException {
        var client = new FakeClient();
        client.streamDelayMs = 50;
        client.streams.add(List.of(A2AStreamEvent.ofStatusUpdate(status("task-1", "ctx-1", TaskState.COMPLETED, "one"))));
        client.streams.add(List.of(A2AStreamEvent.ofStatusUpdate(status("task-2", "ctx-1", TaskState.COMPLETED, "two"))));
        var tool = tool(client, new InMemoryRemoteAgentContextStore());
        var context = ExecutionContext.builder().sessionId("local-1").build();
        var done = new CountDownLatch(2);

        new Thread(() -> executeAndCountDown(tool, context, done), "remote-tool-test-1").start();
        new Thread(() -> executeAndCountDown(tool, context, done), "remote-tool-test-2").start();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, client.maxActiveStreams.get());
    }

    private void executeAndCountDown(A2ARemoteAgentToolCall tool, ExecutionContext context, CountDownLatch done) {
        try {
            tool.execute("{\"query\":\"run\"}", context);
        } finally {
            done.countDown();
        }
    }

    private A2ARemoteAgentToolCall tool(FakeClient client, InMemoryRemoteAgentContextStore store) {
        return tool(client, store, descriptor());
    }

    private A2ARemoteAgentToolCall tool(FakeClient client, InMemoryRemoteAgentContextStore store,
                                        A2ARemoteAgentDescriptor descriptor) {
        return A2ARemoteAgentToolCall.builder()
                .descriptor(descriptor)
                .client(client)
                .contextStore(store)
                .build();
    }

    private A2ARemoteAgentDescriptor descriptor() {
        return A2ARemoteAgentDescriptor.builder()
                .id("enterprise")
                .toolName("server_default_assistant")
                .toolDescription("Use server agent")
                .timeout(Duration.ofSeconds(2))
                .build();
    }

    private Task task(String taskId, String contextId, TaskState state) {
        return task(taskId, contextId, state, null);
    }

    private Task task(String taskId, String contextId, TaskState state, String text) {
        var task = new Task();
        task.id = taskId;
        task.contextId = contextId;
        task.status = text == null ? TaskStatus.of(state) : TaskStatus.of(state, ai.core.api.a2a.Message.agent(text));
        return task;
    }

    private TaskArtifactUpdateEvent artifact(String taskId, String contextId, String text) {
        var update = new TaskArtifactUpdateEvent();
        update.taskId = taskId;
        update.contextId = contextId;
        update.artifact = Artifact.text(text);
        return update;
    }

    private TaskStatusUpdateEvent status(String taskId, String contextId, TaskState state, String text) {
        var update = new TaskStatusUpdateEvent();
        update.taskId = taskId;
        update.contextId = contextId;
        update.status = text == null ? TaskStatus.of(state) : TaskStatus.of(state, ai.core.api.a2a.Message.agent(text));
        return update;
    }

    private static final class FakeClient implements RemoteAgentClient {
        final Queue<List<A2AStreamEvent>> streams = new ArrayDeque<>();
        final List<SendMessageRequest> requests = new ArrayList<>();
        final List<TestSubscription> subscriptions = new ArrayList<>();
        final AtomicInteger activeStreams = new AtomicInteger();
        final AtomicInteger maxActiveStreams = new AtomicInteger();
        long streamDelayMs;

        @Override
        public ai.core.api.a2a.AgentCard getAgentCard() {
            throw new UnsupportedOperationException();
        }

        @Override
        public A2AInvocationResult send(SendMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flow.Publisher<A2AStreamEvent> stream(SendMessageRequest request) {
            requests.add(request);
            var events = streams.remove();
            return subscriber -> {
                var subscription = new TestSubscription();
                subscriptions.add(subscription);
                subscriber.onSubscribe(subscription);
                var thread = new Thread(() -> publish(subscriber, events), "fake-a2a-stream");
                thread.setDaemon(true);
                thread.start();
            };
        }

        private void publish(Flow.Subscriber<? super A2AStreamEvent> subscriber, List<A2AStreamEvent> events) {
            activeStreams.incrementAndGet();
            maxActiveStreams.accumulateAndGet(activeStreams.get(), Math::max);
            try {
                if (streamDelayMs > 0) new CountDownLatch(1).await(streamDelayMs, TimeUnit.MILLISECONDS);
                events.forEach(subscriber::onNext);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                subscriber.onError(e);
                return;
            } finally {
                activeStreams.decrementAndGet();
            }
            subscriber.onComplete();
        }

        @Override
        public Task getTask(GetTaskRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Task cancelTask(CancelTaskRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestSubscription implements Flow.Subscription {
        volatile boolean cancelled;

        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
