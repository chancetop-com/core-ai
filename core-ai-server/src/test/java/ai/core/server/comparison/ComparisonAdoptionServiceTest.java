package ai.core.server.comparison;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.ComparisonStatus;
import ai.core.server.comparison.domain.ComparisonVariant;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The adoption side of a comparison: adopting overwrites the live agent through the publish flow and
 * keeps the replaced config as a revert snapshot; reverting republishes that snapshot but refuses once
 * the agent was touched again.
 *
 * @author stephen
 */
class ComparisonAdoptionServiceTest {
    private static final String AGENT_ID = "agent-1";
    private static final String USER_ID = "user-1";
    private static final ZonedDateTime TEST_START = ZonedDateTime.parse("2026-10-16T02:00:00Z");

    private static AgentPublishedConfig config(String model) {
        var config = new AgentPublishedConfig();
        config.model = model;
        config.systemPrompt = "prompt-" + model;
        return config;
    }

    private static ComparisonVariant variant(String model) {
        var variant = new ComparisonVariant();
        variant.label = model;
        variant.config = config(model);
        return variant;
    }

    private static AgentComparison comparison() {
        var comparison = new AgentComparison();
        comparison.id = "cmp-1";
        comparison.agentId = AGENT_ID;
        comparison.createdBy = USER_ID;
        comparison.status = ComparisonStatus.RUNNING;
        comparison.agentPublishedAt = TEST_START;
        comparison.variantA = variant("a-model");
        comparison.variantB = variant("b-model");
        return comparison;
    }

    private static AgentDefinition agent(String model) {
        var agent = new AgentDefinition();
        agent.id = AGENT_ID;
        agent.model = model;
        agent.systemPrompt = "prompt-" + model;
        agent.publishedAt = TEST_START;
        agent.updatedAt = TEST_START;
        return agent;
    }

    @SuppressWarnings("unchecked")
    private final MongoCollection<AgentDefinition> agents = mock(MongoCollection.class);
    private final AgentDefinitionService agentDefinitionService = mock(AgentDefinitionService.class);
    private final ComparisonAdoptionService service = new ComparisonAdoptionService();

    @BeforeEach
    void setUp() {
        service.agentDefinitionCollection = agents;
        service.agentDefinitionService = agentDefinitionService;
    }

    @Test
    void adoptSnapshotsTheLiveConfigBeforeOverwritingIt() {
        var comparison = comparison();
        var live = agent("a-model");
        var published = agent("b-model");
        published.updatedAt = TEST_START.plusHours(1);
        when(agents.get(AGENT_ID)).thenReturn(Optional.of(live)).thenReturn(Optional.of(published));

        service.adopt(comparison, "b", false, USER_ID);

        assertEquals("a-model", comparison.preAdoptConfig.model);
        assertEquals(TEST_START.plusHours(1), comparison.adoptAgentUpdatedAt);
        assertEquals("b-model", live.model, "the adopted variant is applied to the live agent");
        verify(agentDefinitionService).publish(AGENT_ID, USER_ID);
    }

    @Test
    void adoptRefusesWhenTheAgentChangedSinceTheTestStarted() {
        var comparison = comparison();
        var live = agent("a-model");
        live.publishedAt = TEST_START.plusHours(1);
        when(agents.get(AGENT_ID)).thenReturn(Optional.of(live));

        assertThrows(BadRequestException.class, () -> service.adopt(comparison, "b", false, USER_ID));
        verify(agentDefinitionService, never()).publish(any(), any());
    }

    @Test
    void adoptWithForceStillAppliesTheVariant() {
        var comparison = comparison();
        var live = agent("a-model");
        live.publishedAt = TEST_START.plusHours(1);
        var published = agent("b-model");
        published.updatedAt = TEST_START.plusHours(2);
        when(agents.get(AGENT_ID)).thenReturn(Optional.of(live)).thenReturn(Optional.of(published));

        service.adopt(comparison, "b", true, USER_ID);

        assertEquals("b-model", live.model);
        verify(agentDefinitionService).publish(AGENT_ID, USER_ID);
    }

    @Test
    void revertRepublishesTheSnapshotTakenBeforeTheAdoption() {
        var comparison = comparison();
        comparison.adoptedVariant = "b";
        comparison.preAdoptConfig = config("a-model");
        comparison.adoptAgentUpdatedAt = TEST_START.plusHours(1);
        var live = agent("b-model");
        live.updatedAt = TEST_START.plusHours(1);
        when(agents.get(AGENT_ID)).thenReturn(Optional.of(live));

        service.revert(comparison, USER_ID);

        assertEquals("a-model", live.model);
        assertEquals("prompt-a-model", live.systemPrompt);
        verify(agentDefinitionService).publish(AGENT_ID, USER_ID);
    }

    @Test
    void revertRefusesWhenTheAgentWasTouchedAfterTheAdoption() {
        var comparison = comparison();
        comparison.adoptedVariant = "b";
        comparison.preAdoptConfig = config("a-model");
        comparison.adoptAgentUpdatedAt = TEST_START.plusHours(1);
        var live = agent("b-model");
        live.updatedAt = TEST_START.plusHours(2);
        when(agents.get(AGENT_ID)).thenReturn(Optional.of(live));

        assertThrows(BadRequestException.class, () -> service.revert(comparison, USER_ID));
        verify(agentDefinitionService, never()).publish(any(), any());
    }
}
