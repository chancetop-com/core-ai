package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDependencyAccessPolicyTest {
    @Test
    void sessionExecutableKeepsPersonalAssistantProvenance() {
        var fork = personalAssistant();
        fork.userId = "user-1";

        var executable = AgentDependencyAccessPolicy.executableSessionAgent(fork, "user-1");

        assertEquals("assistant:user-1", executable.id);
        assertEquals(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, executable.forkedFrom);
        assertTrue(PersonalAssistantService.isPersonalAssistant(executable));
        assertNotNull(executable.publishedConfig);
    }

    @Test
    void publishedSessionExecutableKeepsPersonalAssistantProvenance() {
        var fork = personalAssistant();
        fork.userId = "user-9";
        fork.status = AgentStatus.PUBLISHED;
        fork.publishedConfig = new AgentPublishedConfig();

        var executable = AgentDependencyAccessPolicy.executableSessionAgent(fork, "user-1");

        assertEquals(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, executable.forkedFrom);
        assertTrue(PersonalAssistantService.isPersonalAssistant(executable));
    }

    @Test
    void sessionExecutableOfUserAgentIsNotAPersonalAssistant() {
        var agent = new AgentDefinition();
        agent.id = "my-agent";
        agent.userId = "user-1";
        agent.type = DefinitionType.AGENT;

        var executable = AgentDependencyAccessPolicy.executableSessionAgent(agent, "user-1");

        assertFalse(PersonalAssistantService.isPersonalAssistant(executable));
    }

    private AgentDefinition personalAssistant() {
        var definition = new AgentDefinition();
        definition.id = "assistant:user-1";
        definition.name = "Assistant";
        definition.type = DefinitionType.AGENT;
        definition.status = AgentStatus.PUBLISHED;
        definition.forkedFrom = PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID;
        return definition;
    }
}
