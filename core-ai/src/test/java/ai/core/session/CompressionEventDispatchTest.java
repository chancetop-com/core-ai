package ai.core.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.CompressionEvent;
import ai.core.context.CompressionConfig;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compression a session runs must reach its listeners as events, otherwise the server has no way
 * to tell a client that the conversation was summarized.
 *
 * @author stephen
 */
class CompressionEventDispatchTest {

    private static List<Message> conversation() {
        var messages = new ArrayList<Message>();
        messages.add(Message.of(RoleType.SYSTEM, "you are a test agent"));
        for (int i = 0; i < 4; i++) {
            messages.add(Message.of(RoleType.USER, "user question " + i));
            messages.add(Message.of(RoleType.ASSISTANT, "assistant answer " + i));
        }
        messages.add(Message.of(RoleType.USER, "latest question"));
        messages.add(Message.of(RoleType.ASSISTANT, "latest answer"));
        return messages;
    }

    private static Agent agent(MockLLMProvider provider) {
        return Agent.builder()
            .llmProvider(provider)
            .maxTurn(1)
            .compression(new CompressionConfig(true, 0.5, 2, 10, 1000, null))
            .build();
    }

    private static String describe(List<CompressionEvent> events) {
        var text = new StringBuilder();
        for (var event : events) {
            text.append('[').append(event.completed).append(' ').append(event.beforeCount).append("->")
                .append(event.afterCount).append(']');
        }
        return text.toString();
    }

    @Test
    void sessionDispatchesCompressionEvents() {
        var provider = new MockLLMProvider();
        provider.addResponse(CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "summary of the earlier turns"))),
            new Usage(10, 20, 30)
        ));
        var agent = agent(provider);
        var session = new InProcessAgentSession("compression-session", agent, true, new InMemoryToolPermissionStore());
        var events = new CopyOnWriteArrayList<CompressionEvent>();
        session.onEvent(new AgentEventListener() {
            @Override
            public void onCompression(CompressionEvent event) {
                events.add(event);
            }
        });

        var messages = conversation();
        var compressed = agent.getCompression().forceCompress(messages);

        assertEquals(2, events.size(), () -> "expected a started and a completed event, got " + describe(events));
        var started = events.getFirst();
        assertFalse(started.completed);
        assertEquals(messages.size(), started.beforeCount);
        assertTrue(started.afterCount < messages.size(), "the started event reports the messages being summarized");
        var completed = events.getLast();
        assertTrue(completed.completed);
        assertEquals(messages.size(), completed.beforeCount);
        assertEquals(compressed.size(), completed.afterCount);
        assertEquals(1000, completed.maxContextTokens.intValue(), "the event carries the context window the trigger was decided on");
        assertEquals(0.5, completed.triggerThreshold, 0.0001);
        assertEquals(started.contextTokens, completed.contextTokens, "the context usage is the one compression measured");
        assertTrue(completed.contextTokens > 0);
    }
}
