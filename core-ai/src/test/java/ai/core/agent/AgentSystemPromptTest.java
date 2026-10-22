package ai.core.agent;

import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Xander
 */
class AgentSystemPromptTest {
    private static final String MEMORY = "## Indexed Memory\n- m1 [methods] before submit, grep '{{' to confirm no placeholder is left";

    @Test
    void sectionWithUnclosedMustacheTagIsKeptVerbatim() {
        var agent = builder()
                .systemPrompt("Serve {{shop}}.")
                .systemPromptSection(() -> MEMORY)
                .build();

        assertEquals("Serve Choice.\n\n" + MEMORY, systemText(agent, Map.of("shop", "Choice")));
    }

    @Test
    void sectionsWithoutTemplateAreNotRendered() {
        var agent = builder()
                .systemPromptSection(() -> "use {{name}} literally")
                .systemPromptSection(() -> MEMORY)
                .build();

        assertEquals("", agent.getSystemPrompt());
        assertEquals("use {{name}} literally\n\n" + MEMORY, systemText(agent, Map.of("name", "x")));
    }

    @Test
    void appendixSetAfterBuildIsNotRendered() {
        var agent = builder().systemPrompt("base").build();

        agent.setSystemPromptAppendix(agent.getSystemPromptAppendix() + "\n# Notices\n- \"{{ weekly report\"");

        assertEquals("base\n\n# Notices\n- \"{{ weekly report\"", systemText(agent, Map.of()));
    }

    @Test
    void emptyPromptFallsBackToDefault() {
        var agent = builder().build();

        assertEquals("you are a helpful assistant", systemText(agent, Map.of()));
    }

    private AgentBuilder builder() {
        return Agent.builder()
                .name("prompt-test")
                .description("test agent")
                .llmProvider(new MockLLMProvider());
    }

    private String systemText(Agent agent, Map<String, Object> variables) {
        return agent.buildSystemMessage(variables).content.getFirst().text;
    }
}
