package ai.core.server.run;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.TriggerType;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.skill.SkillService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.skill.SkillMetadata;
import ai.core.tool.registry.ToolRegistryFactory;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRunnerTest {
    @Test
    void safeNodeNameSanitizesDisplayNamesToToolSafeForm() {
        assertEquals("Xander-test-(1)", name("Xander-test (1)"));
        assertEquals("a-b-c", name("a <b>/c"));
        assertEquals("plain-name", name("plain-name"));
    }

    @Test
    void safeNodeNameFallsBackToIdWhenNameMissing() {
        assertEquals("agent-id-1", name(null));
        assertEquals("agent-id-1", name("   "));
    }

    @Test
    void toolResolutionReceivesAuthenticatedRunCallerIdentity() {
        var tool = ToolRef.of("llm-call:target", ToolSourceType.LLM_CALL);
        var config = new AgentPublishedConfig();
        config.tools = List.of(tool);
        var definition = new AgentDefinition();
        var run = new AgentRun();
        run.id = "run-1";
        run.userId = "caller-1";
        var expected = ToolRegistryFactory.createEmpty();
        var registryService = mock(ToolRegistryService.class);
        when(registryService.resolveToToolRegistry(config.tools, run.id, run.userId)).thenReturn(expected);
        var builder = new AgentRunBuilder();
        builder.toolRegistryService = registryService;

        var actual = builder.resolveToolRegistry(config, definition, run);

        assertSame(expected, actual);
        verify(registryService).resolveToToolRegistry(config.tools, run.id, run.userId);
    }

    @Test
    void runRecordUsesAuthenticatedCallerInsteadOfDefinitionOwner() {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = "agent-owner";
        var runner = new AgentRunner();

        AgentRun run = runner.createRunRecord(definition, "input", TriggerType.MANUAL, null, "caller-1");

        assertEquals("caller-1", run.userId);
        assertEquals("agent-owner", definition.userId);
    }

    @Test
    void publishedConfigWithoutToolsDoesNotFallBackToEditableToolRefs() {
        var editableTool = ToolRef.of("llm-call:editable-only", ToolSourceType.LLM_CALL);
        var config = new AgentPublishedConfig();
        var definition = new AgentDefinition();
        definition.tools = List.of(editableTool);
        var run = new AgentRun();
        run.id = "run-1";
        run.userId = "caller-1";
        var expected = ToolRegistryFactory.createEmpty();
        var registryService = mock(ToolRegistryService.class);
        when(registryService.resolveToToolRegistry(List.of(), run.id, run.userId)).thenReturn(expected);
        var builder = new AgentRunBuilder();
        builder.toolRegistryService = registryService;

        var actual = builder.resolveToolRegistry(config, definition, run);

        assertSame(expected, actual);
        verify(registryService).resolveToToolRegistry(List.of(), run.id, run.userId);
    }

    @Test
    void rejectsLegacyPublishedSkillsBeforeAnyRunnerSideEffect() {
        var definition = new AgentDefinition();
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.skillIds = List.of("historical-skill");
        var runner = new AgentRunner();

        var error = assertThrows(IllegalArgumentException.class,
            () -> runner.requireTrustedDefinitionSkills(definition));

        assertEquals("agent is unavailable", error.getMessage());
    }

    @Test
    void acceptsValidatedFrozenSkillsWithoutCallerReauthorization() {
        var definition = new AgentDefinition();
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.skillIds = List.of("frozen-skill");
        definition.publishedConfig.skillValidationVersion = 1;
        var runner = new AgentRunner();
        runner.skillService = mock(SkillService.class);

        assertDoesNotThrow(() -> runner.requireTrustedDefinitionSkills(definition));

        verifyNoInteractions(runner.skillService);
    }

    @Test
    void scheduledRawDraftRejectsUnknownSkillBeforeRunSideEffects() {
        assertScheduledRawSkillUnavailable("unknown-skill");
    }

    @Test
    void scheduledRawDraftAllowsOwnerSkillBeforeStartingRun() {
        var harness = runnerHarness();
        var definition = rawDefinition("owner-skill");
        when(harness.skillService.resolveAccessibleSkills(List.of("owner-skill"), "owner"))
            .thenReturn(List.of(SkillMetadata.builder("Owner skill", "", "owner/skill").build()));
        var firstSideEffect = new IllegalStateException("run insert reached");
        doThrow(firstSideEffect).when(harness.runCollection).insert(any());

        var error = assertThrows(IllegalStateException.class,
            () -> runScheduledWithChannel(harness.runner, definition));

        assertSame(firstSideEffect, error);
        verify(harness.skillService).resolveAccessibleSkills(List.of("owner-skill"), "owner");
    }

    @Test
    void scheduledValidatedPublishedFrozenSkillsDoNotReauthorizeAgainstCurrentOwner() {
        var harness = runnerHarness();
        var definition = rawDefinition("edited-private-skill");
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.skillIds = List.of("frozen-skill");
        definition.publishedConfig.skillValidationVersion = 1;
        var firstSideEffect = new IllegalStateException("run insert reached");
        doThrow(firstSideEffect).when(harness.runCollection).insert(any());

        var error = assertThrows(IllegalStateException.class,
            () -> runScheduledWithChannel(harness.runner, definition));

        assertSame(firstSideEffect, error);
        verifyNoInteractions(harness.skillService);
    }

    private void assertScheduledRawSkillUnavailable(String skillId) {
        var harness = runnerHarness();
        var definition = rawDefinition(skillId);
        when(harness.skillService.resolveAccessibleSkills(List.of(skillId), "owner"))
            .thenThrow(new ForbiddenException("skill is unavailable"));

        var error = assertThrows(ForbiddenException.class,
            () -> runScheduledWithChannel(harness.runner, definition));

        assertEquals("skill is unavailable", error.getMessage());
        verifyNoInteractions(harness.runCollection, harness.sandboxService, harness.builder);
    }

    private String runScheduledWithChannel(AgentRunner runner, AgentDefinition definition) {
        return runner.run(definition, "scheduled input", TriggerType.SCHEDULE, "schedule-1", Map.of(),
            new AgentRunner.ChannelTarget("channel-1", "recipient-1"));
    }

    private AgentDefinition rawDefinition(String skillId) {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = "owner";
        definition.skillIds = List.of(skillId);
        return definition;
    }

    @SuppressWarnings("unchecked")
    private RunnerHarness runnerHarness() {
        var runner = new AgentRunner();
        runner.skillService = mock(SkillService.class);
        runner.agentRunCollection = mock(MongoCollection.class);
        runner.sandboxService = mock(SandboxService.class);
        runner.builder = mock(AgentRunBuilder.class);
        return new RunnerHarness(runner, runner.skillService, runner.agentRunCollection,
            runner.sandboxService, runner.builder);
    }

    private String name(String displayName) {
        var definition = new AgentDefinition();
        definition.id = "id-1";
        definition.name = displayName;
        return AgentRunBuilder.safeNodeName(definition);
    }

    private record RunnerHarness(AgentRunner runner, SkillService skillService,
                                 MongoCollection<AgentRun> runCollection, SandboxService sandboxService,
                                 AgentRunBuilder builder) {
    }
}
