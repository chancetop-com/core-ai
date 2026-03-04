package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author xander
 */
class CancellationTest {

    private static CompletionResponse simpleResponse(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(10, 20, 30)
        );
    }

    private static CompletionResponse toolCallResponse() {
        String json = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","name":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"test_tool","arguments":"{}"},"index":null}]}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
                """;
        return ai.core.utils.JsonUtil.fromJson(CompletionResponse.class, json);
    }

    @Test
    void agentCancelSetsFlagAndResetsCorrectly() {
        var provider = new MockLLMProvider();
        provider.addResponse(simpleResponse("hello"));

        var agent = Agent.builder()
                .llmProvider(provider)
                .build();

        assertFalse(agent.isCancelled());

        agent.cancel();
        assertTrue(agent.isCancelled());

        agent.resetCancellation();
        assertFalse(agent.isCancelled());
    }

    @Test
    void agentCancelClosesActiveConnection() {
        var provider = new MockLLMProvider();
        provider.addResponse(simpleResponse("hello"));

        var closed = new boolean[]{false};
        var callback = new SessionStreamingCallback("test", event -> { });
        callback.setActiveConnection(() -> closed[0] = true);

        var agent = Agent.builder()
                .llmProvider(provider)
                .streamingCallback(callback)
                .build();

        agent.cancel();

        assertTrue(closed[0], "active connection should be closed on cancel");
        assertTrue(callback.isCancelled());
    }

    @Test
    void sessionStreamingCallbackResetClearsCancelledState() {
        var callback = new SessionStreamingCallback("test", event -> { });
        callback.cancelConnection();
        assertTrue(callback.isCancelled());

        callback.reset();
        assertFalse(callback.isCancelled());
    }

    @Test
    void cancelTurnDuringLLMCallDispatchesCancelledEvent() throws InterruptedException {
        var slowProvider = new SlowMockLLMProvider(2000);
        slowProvider.addResponse(simpleResponse("should not see this"));

        var agent = Agent.builder()
                .llmProvider(slowProvider)
                .build();

        var session = new InProcessAgentSession("test-cancel", agent, true, new ToolPermissionStore(Path.of("/tmp/test-perms.json")));
        var events = new CopyOnWriteArrayList<AgentEvent>();
        var latch = new CountDownLatch(1);

        session.onEvent(new AgentEventListener() {
            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                events.add(event);
                latch.countDown();
            }
        });

        session.sendMessage("hello");

        // wait for LLM call to start, then cancel
        Thread.sleep(200);
        session.cancelTurn();

        assertTrue(latch.await(10, TimeUnit.SECONDS), "turn should complete after cancel");

        var turnComplete = events.stream()
                .filter(e -> e instanceof TurnCompleteEvent)
                .map(e -> (TurnCompleteEvent) e)
                .findFirst()
                .orElseThrow();
        assertTrue(turnComplete.cancelled, "turn should be marked as cancelled");

        session.close();
    }

    @Test
    void cancelDuringChatTurnsBreaksLoop() {
        var provider = new MockLLMProvider();
        provider.addResponse(toolCallResponse());
        provider.addResponse(simpleResponse("second turn"));

        var agent = Agent.builder()
                .llmProvider(provider)
                .maxTurn(5)
                .build();

        // cancel immediately so chatTurns loop breaks at the top
        agent.cancel();
        agent.run("hello");

        assertEquals(0, provider.getCallCount());
    }

    @Test
    void resetCancellationAllowsNextMessage() throws InterruptedException {
        var provider = new MockLLMProvider();
        provider.addResponse(simpleResponse("first"));
        provider.addResponse(simpleResponse("second"));

        var agent = Agent.builder()
                .llmProvider(provider)
                .build();

        var session = new InProcessAgentSession("test-reset", agent, true, new ToolPermissionStore(Path.of("/tmp/test-perms2.json")));
        var completedEvents = new CopyOnWriteArrayList<TurnCompleteEvent>();

        session.onEvent(new AgentEventListener() {
            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                completedEvents.add(event);
            }
        });

        var latch1 = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                latch1.countDown();
            }
        });
        session.sendMessage("first");
        assertTrue(latch1.await(10, TimeUnit.SECONDS));

        var latch2 = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                latch2.countDown();
            }
        });
        session.sendMessage("second");
        assertTrue(latch2.await(10, TimeUnit.SECONDS));

        assertEquals(2, provider.getCallCount());
        session.close();
    }

    /**
     * Mock provider that delays response to simulate slow LLM call
     */
    static class SlowMockLLMProvider extends MockLLMProvider {
        private final long delayMs;

        SlowMockLLMProvider(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted during slow LLM call", e);
            }
            return super.doCompletionStream(request, callback);
        }
    }
}
