package ai.core.server.a2a;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerA2AServiceTest {
    @Test
    void agentCardUsesPublishedServerToolsAsSkills() {
        var service = new ServerA2AService();
        service.agentDefinitionService = new FakeAgentDefinitionService(definition());

        var card = service.agentCard("agent-1");

        assertEquals("reviewer", card.name);
        assertEquals("review code", card.description);
        assertEquals("agent-1", card.supportedInterfaces.getFirst().tenant);
        assertTrue(card.capabilities.streaming);
        assertEquals(List.of("text/plain", "application/json"), card.defaultInputModes);
        assertEquals("reviewer", card.skills.getFirst().name);
        assertTrue(card.skills.stream().anyMatch(skill -> "jira-search".equals(skill.id)));
        assertTrue(card.skills.stream().anyMatch(skill -> "github-mcp".equals(skill.id)));
    }

    private AgentDefinition definition() {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.name = "reviewer";
        definition.description = "review code";
        definition.publishedAt = ZonedDateTime.parse("2026-05-06T00:00:00Z");
        var published = new AgentPublishedConfig();
        published.tools = List.of(
                ToolRef.of("jira-search", ToolSourceType.API),
                ToolRef.of("github-mcp", ToolSourceType.MCP, "github")
        );
        definition.publishedConfig = published;
        return definition;
    }

    private static final class FakeAgentDefinitionService extends AgentDefinitionService {
        private final AgentDefinition definition;

        FakeAgentDefinitionService(AgentDefinition definition) {
            this.definition = definition;
        }

        @Override
        public AgentDefinition getEntity(String id) {
            assertEquals(definition.id, id);
            return definition;
        }
    }
}
