package ai.core.agent;

import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.rag.RagConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Xander
 */
class AgentUserTurnPromptTest {
    private static final String QUERY = "check the report: grep '{{' must find nothing, keep &lt;title&gt; as typed";

    @Test
    void queryWithUnclosedMustacheTagReachesTheModelVerbatim() {
        var provider = new MockLLMProvider();
        provider.addResponse(CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "ok"))),
                new Usage(10, 2, 12)));
        var agent = Agent.builder()
                .name("user-turn")
                .description("test agent")
                .systemPrompt("test")
                .llmProvider(provider)
                .build();

        agent.run(QUERY, ExecutionContext.empty());

        var userText = agent.getMessages().stream()
                .filter(message -> message.role == RoleType.USER)
                .map(Message::getTextContent)
                .findFirst()
                .orElseThrow();
        assertTrue(userText.contains(QUERY), userText);
    }

    @Test
    void templatesAroundTheQueryAreStillRendered() {
        var scopes = Map.<String, Object>of("shop", "Choice", RagConfig.AGENT_RAG_CONTEXT_PLACEHOLDER, "doc {{x}}");

        var prompt = PromptRenderer.renderUserTurn("Shop {{shop}}: ", "find {{", RagConfig.AGENT_RAG_CONTEXT_TEMPLATE, scopes);

        assertEquals("Shop Choice: find {{\nContext:doc {{x}}\n\n", prompt);
    }

    @Test
    void queryWithoutTemplatesIsReturnedAsIs() {
        assertEquals(QUERY, PromptRenderer.renderUserTurn("", QUERY, null, Map.of()));
    }
}
