package ai.core.agent;

import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that LLM usage/cost from tools that internally call the LLM (e.g. caption_image)
 * is accumulated into the agent session usage and cost.
 *
 * @author stephen
 */
class AgentToolLlmUsageTest {

    @Test
    void toolLlmUsageAccumulatesIntoSessionUsageAndCost() {
        var provider = new MockLLMProvider();
        // turn 1: main loop decides to call caption_image
        var toolCall = FunctionCall.of("call_1", "function", "caption_image",
                "{\"query\": \"what is this\", \"url\": \"data:image/png;base64,QUJD\"}");
        provider.addResponse(CompletionResponse.of(
                List.of(Choice.of(FinishReason.TOOL_CALLS,
                        Message.of(RoleType.ASSISTANT, "", null, null, List.of(toolCall)))),
                new Usage(100, 20, 120)));
        // inside caption_image: the vision model call
        provider.addResponse(CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "the image shows a cat"))),
                new Usage(120, 30, 150)));
        // turn 2: main loop final answer
        provider.addResponse(CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "done"))),
                new Usage(80, 10, 90)));

        var agent = Agent.builder()
                .name("usage-accumulate")
                .description("test agent")
                .systemPrompt("test")
                .llmProvider(provider)
                .model("mock-model")
                .multiModalModel("gpt-4o")
                .build();

        agent.run("look at this image", ExecutionContext.empty());

        assertEquals(300, agent.getCurrentTokenUsage().getPromptTokens());
        assertEquals(60, agent.getCurrentTokenUsage().getCompletionTokens());
        assertEquals(360, agent.getCurrentTokenUsage().getTotalTokens());
        assertTrue(agent.getCurrentCostUsd() > 0);
    }
}
