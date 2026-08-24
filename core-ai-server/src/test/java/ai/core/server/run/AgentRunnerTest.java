package ai.core.server.run;

import ai.core.agent.Agent;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChannelTarget;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.TriggerType;
import ai.core.sandbox.Sandbox;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.skill.SkillService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.skill.SkillMetadata;
import ai.core.tool.registry.ToolRegistryFactory;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRunnerTest {
    @Test
    void successfulAgentExecutionMarksRunCompletedOnce() throws InterruptedException {
        var harness = executionHarness();
        when(harness.tracer.runAgentWithTrace(any(), any(), any(), any())).thenReturn("output");

        try {
            harness.runner.run(executableDefinition(30), "input", TriggerType.WORKFLOW);

            assertTrue(harness.builder.terminalUpdate.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(RunStatus.COMPLETED), harness.builder.statuses);
            assertEquals(RunStatus.COMPLETED, harness.builder.run.status);
            assertEquals("output", harness.builder.run.output);
        } finally {
            harness.runner.shutdown();
        }
    }

    @Test
    void thrownAgentExecutionMarksRunFailed() throws InterruptedException {
        var harness = executionHarness();
        when(harness.tracer.runAgentWithTrace(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("sse timeout"));

        try {
            harness.runner.run(executableDefinition(30), "input", TriggerType.WORKFLOW);

            assertTrue(harness.builder.terminalUpdate.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(RunStatus.FAILED), harness.builder.statuses);
            assertEquals(RunStatus.FAILED, harness.builder.run.status);
            assertEquals("sse timeout", harness.builder.run.error);
        } finally {
            harness.runner.shutdown();
        }
    }

    @Test
    void timeoutWinsWhenAgentReturnsAfterDeadline() throws InterruptedException {
        var harness = executionHarness();
        var executionStarted = new CountDownLatch(1);
        var releaseExecution = new CountDownLatch(1);
        when(harness.tracer.runAgentWithTrace(any(), any(), any(), any())).thenAnswer(invocation -> {
            executionStarted.countDown();
            releaseExecution.await(5, TimeUnit.SECONDS);
            return "late output";
        });

        try {
            harness.runner.run(executableDefinition(1), "input", TriggerType.WORKFLOW);

            assertTrue(executionStarted.await(1, TimeUnit.SECONDS));
            assertTrue(harness.builder.terminalUpdate.await(2, TimeUnit.SECONDS));
            assertEquals(RunStatus.TIMEOUT, harness.builder.run.status);

            releaseExecution.countDown();
            harness.runner.shutdown();
            assertEquals(List.of(RunStatus.TIMEOUT), harness.builder.statuses);
        } finally {
            releaseExecution.countDown();
            harness.runner.shutdown();
        }
    }

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
            new ChannelTarget("channel-1", "recipient-1"));
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

    @SuppressWarnings("unchecked")
    private ExecutionHarness executionHarness() {
        var runner = new AgentRunner();
        var agent = mock(Agent.class);
        var builder = new RecordingRunBuilder(agent);
        var tracer = mock(AgentRunTracer.class);
        runner.skillService = mock(SkillService.class);
        runner.agentRunCollection = mock(MongoCollection.class);
        runner.sandboxService = mock(SandboxService.class);
        runner.builder = builder;
        runner.tracer = tracer;
        return new ExecutionHarness(runner, builder, tracer);
    }

    private AgentDefinition executableDefinition(int timeoutSeconds) {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = "owner";
        definition.type = DefinitionType.AGENT;
        definition.timeoutSeconds = timeoutSeconds;
        return definition;
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

    private record ExecutionHarness(AgentRunner runner, RecordingRunBuilder builder, AgentRunTracer tracer) {
    }

    private static final class RecordingRunBuilder extends AgentRunBuilder {
        private final Agent agent;
        private final CountDownLatch terminalUpdate = new CountDownLatch(1);
        private final List<RunStatus> statuses = new CopyOnWriteArrayList<>();
        private volatile AgentRun run;

        private RecordingRunBuilder(Agent agent) {
            this.agent = agent;
        }

        @Override
        Agent buildAgent(AgentRun runEntity, AgentDefinition definition, Sandbox sandbox,
                         Map<String, Object> variables, List<LLMCallRequest.Attachment> attachments) {
            run = runEntity;
            return agent;
        }

        @Override
        void updateRunStatus(AgentRun runEntity, RunStatus status, String output, String error, Agent ignoredAgent) {
            record(runEntity, status, output, error, null);
        }

        @Override
        void updateRunStatus(AgentRun runEntity, RunStatus status, String output, String error,
                             String errorStack, Agent ignoredAgent) {
            record(runEntity, status, output, error, errorStack);
        }

        @Override
        void extractDatasetRecords(String output, AgentDefinition definition, String runId, String agentId,
                                   ZonedDateTime runStartedAt) {
            // Dataset persistence is outside the terminal-state contract under test.
        }

        private void record(AgentRun runEntity, RunStatus status, String output, String error, String errorStack) {
            runEntity.status = status;
            runEntity.output = output;
            runEntity.error = error;
            runEntity.errorStack = errorStack;
            runEntity.completedAt = ZonedDateTime.now();
            statuses.add(status);
            terminalUpdate.countDown();
        }
    }
}
