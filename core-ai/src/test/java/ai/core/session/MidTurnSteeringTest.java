package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Messages sent while a turn is running are injected before the next model call, within the same
 * turn. The tests pin the two important boundaries: after a whole tool batch, and after a model
 * response that contains no tool call at all.
 *
 * @author stephen
 */
class MidTurnSteeringTest {

    private static InProcessAgentSession session(RecordingProvider provider, ToolCall... tools) {
        var registry = new ToolRegistry();
        if (tools.length > 0) {
            registry.registerProvider(ListToolProvider.of(List.of(tools)));
        }
        var agent = Agent.builder()
                .llmProvider(provider)
                .maxTurn(10)
                .toolRegistry(registry)
                .build();
        var context = ExecutionContext.builder()
                .sessionId("mid-turn-" + System.nanoTime())
                .build();
        agent.setExecutionContext(context);
        return new InProcessAgentSession("mid-turn-" + System.nanoTime(), agent, true, new InMemoryToolPermissionStore());
    }

    private static boolean waitForTurns(RecordingListener listener, int count, int seconds) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (listener.turnCount() < count && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        return listener.turnCount() >= count;
    }

    private static void assertOrdered(List<Message> messages, Object... expected) {
        var cursor = -1;
        for (var i = 0; i < expected.length; i += 2) {
            var text = (String) expected[i];
            var role = (RoleType) expected[i + 1];
            var found = -1;
            for (var j = cursor + 1; j < messages.size(); j++) {
                var message = messages.get(j);
                if (message.role != role) continue;
                if (role == RoleType.TOOL ? text.equals(message.toolCallId)
                        : (message.getTextContent() != null && message.getTextContent().contains(text))) {
                    found = j;
                    break;
                }
            }
            assertTrue(found > cursor, "expected " + role + " '" + text + "' after index " + cursor + " in " + messages);
            cursor = found;
        }
    }

    private static boolean containsUserText(List<Message> messages, String text) {
        return messages.stream().anyMatch(message -> message.role == RoleType.USER
                && message.getTextContent() != null && message.getTextContent().contains(text));
    }

    private static CompletionResponse textResponse(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(10, 20, 30));
    }

    private static CompletionResponse toolCallResponse(String callId, String name, String arguments) {
        var json = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","name":"assistant","tool_calls":[{"id":"%s","type":"function","function":{"name":"%s","arguments":"%s"},"index":null}]}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}"""
                .formatted(callId, name, arguments);
        return ai.core.utils.JsonUtil.fromJson(CompletionResponse.class, json);
    }

    @Test
    void messageSentDuringToolExecutionIsInjectedAfterTheToolResult() throws Exception {
        var block = new BlockingTool();
        var provider = new RecordingProvider();
        provider.addResponse(toolCallResponse("call-1", "block", "{}"));
        provider.addResponse(textResponse("done"));

        var session = session(provider, block);
        var listener = new RecordingListener();
        session.onEvent(listener);
        try {
            session.sendMessage("first prompt");
            assertTrue(block.started.await(10, TimeUnit.SECONDS), "tool must start");

            session.sendMessage("second prompt");
            session.sendMessage("third prompt");
            assertEquals(2, session.pendingUserInputCount());

            block.release.countDown();
            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "turn must complete");

            assertEquals(1, listener.turnCount(), "steering must not start another turn");
            assertEquals(2, provider.requests.size(), "one tool response and one follow-up response");
            var followUp = provider.request(1);
            assertOrdered(followUp, "first prompt", RoleType.USER,
                    "call-1", RoleType.TOOL,
                    "second prompt", RoleType.USER,
                    "third prompt", RoleType.USER);
            assertEquals(0, session.pendingUserInputCount());
        } finally {
            block.release.countDown();
            session.close();
        }
    }

    @Test
    void messageSentDuringFinalTextContinuesTheSameTurn() throws Exception {
        var provider = new RecordingProvider();
        var firstCallStarted = new CountDownLatch(1);
        var releaseFirstCall = new CountDownLatch(1);
        provider.gateFirstCall(firstCallStarted, releaseFirstCall);
        provider.addResponse(textResponse("first answer"));
        provider.addResponse(textResponse("second answer"));

        var session = session(provider);
        var listener = new RecordingListener();
        session.onEvent(listener);
        try {
            session.sendMessage("first prompt");
            assertTrue(firstCallStarted.await(10, TimeUnit.SECONDS), "first model call must start");

            session.sendMessage("steer now");
            assertEquals(1, session.pendingUserInputCount());

            releaseFirstCall.countDown();
            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "turn must complete");

            assertEquals(1, listener.turnCount(), "a text-only reply plus pending input stays in one turn");
            assertEquals(2, provider.requests.size(), "the pending input must trigger another model call");
            assertTrue(containsUserText(provider.request(1), "steer now"), "follow-up request must carry the steer");
            assertEquals("first answer\n\nsecond answer", listener.turns.getFirst().output);
        } finally {
            releaseFirstCall.countDown();
            session.close();
        }
    }

    @Test
    void cancelledTurnRequeuesPendingInputForTheNextTurn() throws Exception {
        var block = new BlockingTool();
        var provider = new RecordingProvider();
        provider.addResponse(toolCallResponse("call-2", "block", "{}"));
        provider.addResponse(textResponse("after cancel"));

        var session = session(provider, block);
        var listener = new RecordingListener();
        session.onEvent(listener);
        try {
            session.sendMessage("first prompt");
            assertTrue(block.started.await(10, TimeUnit.SECONDS), "tool must start");

            session.sendMessage("survive the stop");
            session.cancelTurn();
            block.release.countDown();

            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "cancelled turn must terminate");
            assertTrue(waitForTurns(listener, 2, 10), "pending input must run as the next turn");
            assertTrue(containsUserText(provider.request(1), "survive the stop"),
                    "the message must not be dropped when the turn is cancelled");
        } finally {
            block.release.countDown();
            session.close();
        }
    }

    private static final class RecordingProvider extends MockLLMProvider {
        private final List<List<Message>> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CountDownLatch firstCallStarted;
        private volatile CountDownLatch firstCallRelease;

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test gate");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        void gateFirstCall(CountDownLatch started, CountDownLatch release) {
            firstCallStarted = started;
            firstCallRelease = release;
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            var call = calls.getAndIncrement();
            requests.add(List.copyOf(request.messages));
            if (call == 0 && firstCallStarted != null) {
                firstCallStarted.countDown();
                await(firstCallRelease);
            }
            return super.doCompletion(request);
        }

        List<Message> request(int index) {
            return requests.get(index);
        }
    }

    private static final class BlockingTool extends ToolCall {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingTool() {
            setName("block");
            setDescription("blocks until the test releases it");
            setParameters(List.of());
        }

        @Override
        public ToolCallResult execute(String arguments, ExecutionContext context) {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolCallResult.completed("tool result");
        }

        @Override
        public ToolCallResult execute(String arguments) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingListener implements AgentEventListener {
        private final List<TurnCompleteEvent> turns = new CopyOnWriteArrayList<>();
        private final List<ErrorEvent> errors = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstTurn = new CountDownLatch(1);

        @Override
        public void onTurnComplete(TurnCompleteEvent event) {
            turns.add(event);
            firstTurn.countDown();
        }

        @Override
        public void onError(ErrorEvent event) {
            errors.add(event);
        }

        int turnCount() {
            return turns.size();
        }
    }
}
