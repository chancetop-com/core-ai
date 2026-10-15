package ai.core.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class MaxTurnsReachedTest {

    private static CompletionResponse simpleResponse(String content) {
        return CompletionResponse.of(
                java.util.List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
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
    void userTurnThatExceedsMaxTurnsCompletesWithTheTipInsteadOfAnError() throws InterruptedException {
        var provider = new MockLLMProvider();
        provider.addResponse(toolCallResponse());
        provider.addResponse(toolCallResponse());
        provider.addResponse(simpleResponse("never reached"));

        var agent = Agent.builder()
                .llmProvider(provider)
                .maxTurn(1)
                .build();
        var session = new InProcessAgentSession("test-max-turns", agent, true, new InMemoryToolPermissionStore());
        var completed = new CopyOnWriteArrayList<TurnCompleteEvent>();
        var errors = new CopyOnWriteArrayList<ErrorEvent>();
        var latch = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                completed.add(event);
                latch.countDown();
            }

            @Override
            public void onError(ErrorEvent event) {
                errors.add(event);
            }
        });

        session.sendMessage("hello");

        assertTrue(latch.await(10, TimeUnit.SECONDS), "turn should complete");
        assertEquals(1, completed.size());
        assertTrue(completed.getFirst().maxTurnsReached, "turn complete must carry the max turns tip");
        assertTrue(errors.isEmpty(), "max turns must not surface as an error event");
        session.close();
    }
}
