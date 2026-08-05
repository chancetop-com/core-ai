package ai.core.server.trigger.action;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.TriggerType;
import ai.core.server.run.AgentRunner;
import ai.core.server.skill.SkillService;
import ai.core.server.trigger.domain.Trigger;
import ai.core.skill.SkillMetadata;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RunAgentActionTest {
    @Test
    void rejectsForeignSkillInTriggerOwnersEditableDraft() {
        AgentDefinition definition = definition("owner", AgentStatus.DRAFT);
        definition.skillIds = List.of("foreign-skill");
        var harness = harness(definition);
        when(harness.skillService.resolveAccessibleSkills(List.of("foreign-skill"), "owner"))
            .thenThrow(new ForbiddenException("skill is unavailable"));

        var error = assertThrows(ForbiddenException.class,
            () -> harness.action.execute(trigger("owner"), "payload"));

        assertEquals("skill is unavailable", error.getMessage());
        verifyNoInteractions(harness.runner);
    }

    @Test
    void runsTriggerOwnersEditableDraftAfterExactSkillValidation() {
        AgentDefinition definition = definition("owner", AgentStatus.DRAFT);
        definition.skillIds = List.of("owner-skill");
        var harness = harness(definition);
        when(harness.skillService.resolveAccessibleSkills(List.of("owner-skill"), "owner"))
            .thenReturn(List.of(SkillMetadata.builder("Owner skill", "", "owner/skill").build()));
        when(harness.runner.run(definition, "payload", TriggerType.WEBHOOK, null, null))
            .thenReturn("run-1");

        TriggerActionResult result = harness.action.execute(trigger("owner"), "payload");

        assertEquals("RUNNING", result.status);
        assertEquals("run-1", result.runId);
        verify(harness.skillService).resolveAccessibleSkills(List.of("owner-skill"), "owner");
        verify(harness.runner).run(definition, "payload", TriggerType.WEBHOOK, null, null);
    }

    @Test
    void runsValidatedPublishedFrozenSkillsWithoutViewerReauthorization() {
        AgentDefinition definition = definition("publisher", AgentStatus.PUBLISHED);
        definition.skillIds = List.of("editable-private-skill");
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.skillIds = List.of("frozen-skill");
        definition.publishedConfig.skillValidationVersion = 1;
        var harness = harness(definition);
        when(harness.runner.run(any(AgentDefinition.class), eq("payload"),
            eq(TriggerType.WEBHOOK), eq(null), eq(null))).thenReturn("run-1");

        TriggerActionResult result = harness.action.execute(trigger("viewer"), "payload");

        assertEquals("run-1", result.runId);
        verifyNoInteractions(harness.skillService);
        verify(harness.runner).run(any(AgentDefinition.class), eq("payload"),
            eq(TriggerType.WEBHOOK), eq(null), eq(null));
    }

    private Harness harness(AgentDefinition definition) {
        @SuppressWarnings("unchecked")
        MongoCollection<AgentDefinition> collection = mock(MongoCollection.class);
        when(collection.get(definition.id)).thenReturn(Optional.of(definition));
        var runner = mock(AgentRunner.class);
        var skillService = mock(SkillService.class);
        var action = new RunAgentAction();
        action.agentDefinitionCollection = collection;
        action.agentRunner = runner;
        action.skillService = skillService;
        return new Harness(action, runner, skillService);
    }

    private AgentDefinition definition(String owner, AgentStatus status) {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = owner;
        definition.name = "Agent One";
        definition.type = DefinitionType.AGENT;
        definition.status = status;
        return definition;
    }

    private Trigger trigger(String userId) {
        var trigger = new Trigger();
        trigger.id = "trigger-1";
        trigger.userId = userId;
        trigger.actionConfig = Map.of("agent_id", "agent-1");
        return trigger;
    }

    private record Harness(RunAgentAction action, AgentRunner runner, SkillService skillService) {
    }
}
