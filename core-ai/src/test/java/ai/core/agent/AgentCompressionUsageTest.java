package ai.core.agent;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * @author xander
 */
class AgentCompressionUsageTest {

    @Test
    void compressionSummaryUsageCountsIntoAgentTokensAndCostCallback() {
        var provider = new MockLLMProvider();
        provider.addResponse(summaryResponse());
        var agent = Agent.builder()
                .name("compression-usage")
                .description("test agent")
                .systemPrompt("test")
                .llmProvider(provider)
                .model("test-model")
                .build();
        var callbacks = new AtomicInteger();
        var context = ExecutionContext.builder().sessionId("session-1").build();
        context.setTokenCostCallback(usage -> callbacks.incrementAndGet());
        agent.setExecutionContext(context);

        var messages = conversation(10);
        var result = agent.getCompression().forceCompress(messages);

        assertNotEquals(messages, result);
        assertEquals(1500, agent.getCurrentTokenUsage().getTotalTokens());
        assertEquals(1, callbacks.get());
    }

    private CompletionResponse summaryResponse() {
        var response = new CompletionResponse();
        response.choices = List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "compressed summary")));
        response.usage = new Usage(1200, 300, 1500);
        return response;
    }

    private List<Message> conversation(int turns) {
        List<Message> messages = new ArrayList<>(turns * 2 + 1);
        messages.add(Message.of(RoleType.SYSTEM, "You are a helpful assistant"));
        for (int i = 0; i < turns; i++) {
            messages.add(Message.of(RoleType.USER, "Hello " + i));
            messages.add(Message.of(RoleType.ASSISTANT, "Hi there " + i));
        }
        return messages;
    }
}
