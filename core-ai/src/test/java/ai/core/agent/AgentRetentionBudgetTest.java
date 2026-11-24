package ai.core.agent;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retention pass must rewrite the agent's own conversation, not a copy: the working list is what
 * keeps the image bytes reachable between turns.
 *
 * @author stephen
 */
class AgentRetentionBudgetTest {
    @Test
    void applyRetentionBudgetRewritesTheLiveConversation() {
        var agent = Agent.builder().llmProvider(new MockLLMProvider()).compression(false).build();
        agent.addMessage(userMessage("first", image("A", 4000)));
        agent.addMessage(Message.of(RoleType.ASSISTANT, "ok"));
        agent.addMessage(userMessage("second", image("B", 4000), image("C", 4000)));

        agent.applyRetentionBudget(10_000);

        assertTrue(agent.footprint().inlineImageBytes() <= 10_000);
        assertFalse(agent.getMessages().getFirst().content.stream()
                .anyMatch(part -> part.type == Content.ContentType.IMAGE_URL));
        assertEquals(Content.ContentType.IMAGE_URL, agent.getMessages().getLast().content.get(1).type);
    }

    @Test
    void applyRetentionBudgetDoesNothingWhenTheSessionFits() {
        var agent = Agent.builder().llmProvider(new MockLLMProvider()).compression(false).build();
        agent.addMessage(userMessage("only", image("A", 4000)));

        agent.applyRetentionBudget();

        assertEquals(Content.ContentType.IMAGE_URL, agent.getMessages().getFirst().content.get(1).type);
    }

    private Message userMessage(String text, Content... images) {
        var content = new java.util.ArrayList<Content>();
        content.add(Content.of(text));
        content.addAll(List.of(images));
        return Message.of(new Message.MessageRecord(RoleType.USER, content, null, null, null, null));
    }

    private Content image(String filler, int base64Chars) {
        return Content.of(Content.ImageUrl.of("data:image/png;base64," + filler.repeat(base64Chars), "image/png"));
    }
}
