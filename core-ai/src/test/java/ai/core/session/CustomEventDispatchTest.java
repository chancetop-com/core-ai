package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.CustomEvent;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A custom event emitted during a turn must reach the session's listeners; the session installs its
 * emitter into the execution context so tools (and platform code) can emit without extra wiring.
 *
 * @author stephen
 */
class CustomEventDispatchTest {
    @Test
    void customEventEmittedInsideATurnReachesListeners() throws InterruptedException {
        var provider = new MockLLMProvider();
        provider.addResponse(CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "done"))),
            new Usage(1, 1, 2)
        ));
        var agent = Agent.builder().llmProvider(provider).maxTurn(2).build();
        var emitReason = new AtomicReference<String>();
        agent.addLifecycle(new AbstractLifecycle() {
            @Override
            public void afterModel(CompletionRequest completionRequest, CompletionResponse completionResponse, ExecutionContext executionContext) {
                var emitter = (CustomEventEmitter) executionContext.getCustomVariables().get(CustomEventEmitter.CONTEXT_KEY);
                emitReason.set(emitter.emit("menu_table", "{\"rows\":[1]}", null, null));
            }
        });

        var session = new InProcessAgentSession("custom-event-session", agent, true, new InMemoryToolPermissionStore());
        var events = new CopyOnWriteArrayList<CustomEvent>();
        var turnComplete = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onCustomEvent(CustomEvent event) {
                events.add(event);
            }

            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                turnComplete.countDown();
            }
        });

        session.sendMessage("hello");
        assertTrue(turnComplete.await(10, TimeUnit.SECONDS), "turn should complete");

        assertNull(emitReason.get(), "emitting inside the turn must succeed");
        assertEquals(1, events.size());
        assertEquals("menu_table", events.getFirst().name);
        assertEquals("{\"rows\":[1]}", events.getFirst().data);
        session.close();
    }

    @Test
    void emitFromTheContextIsRejectedOutsideATurn() {
        var agent = Agent.builder().llmProvider(new MockLLMProvider()).build();
        var session = new InProcessAgentSession("custom-event-idle", agent, true, new InMemoryToolPermissionStore());

        var emitter = (CustomEventEmitter) session.agent().getExecutionContext()
            .getCustomVariables().get(CustomEventEmitter.CONTEXT_KEY);

        assertNotNull(emitter, "the session must install its emitter into the execution context");
        assertNotNull(emitter.emit("menu_table", "{}", null, null), "an idle session cannot send custom events");
        session.close();
    }
}
