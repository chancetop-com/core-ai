package ai.core.server.seoops;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class SeoCopilotPolicyTest {
    @Test
    void acceptsOnlyPublishedCapabilityEmptyAgent() {
        var policy = policy(agent());
        assertEquals(Optional.of("agent-safe"), policy.eligibleAgentId());
    }

    @Test
    void rejectsAnyExecutionCapability() {
        var agent = agent();
        agent.publishedConfig.skillIds = List.of("seo-write");
        assertTrue(policy(agent).eligibleAgentId().isEmpty());
        agent.publishedConfig.skillIds = List.of();
        agent.publishedConfig.enableMemory = true;
        assertTrue(policy(agent).eligibleAgentId().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private SeoCopilotPolicy policy(AgentDefinition agent) {
        var policy = new SeoCopilotPolicy();
        policy.runtimeConfig = new SeoOpsRuntimeConfig(true, "agent-safe");
        policy.agentCollection = mock(MongoCollection.class);
        when(policy.agentCollection.get("agent-safe")).thenReturn(Optional.of(agent));
        return policy;
    }

    private AgentDefinition agent() {
        var agent = new AgentDefinition();
        agent.id = "agent-safe";
        agent.type = DefinitionType.AGENT;
        agent.status = AgentStatus.PUBLISHED;
        agent.publishedConfig = new AgentPublishedConfig();
        agent.publishedConfig.tools = List.of();
        agent.publishedConfig.skillIds = List.of();
        agent.publishedConfig.subAgentIds = List.of();
        agent.publishedConfig.datasetConfig = List.of();
        agent.publishedConfig.enableMemory = false;
        return agent;
    }
}
