package ai.core.server.run;

import ai.core.api.server.run.AgentCallRequest;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.api.server.run.TriggerRunRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.skill.SkillService;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunServiceTest {
    @Test
    void triggerThreadsAuthenticatedCallerIntoAgentRunner() {
        var definition = definition();
        var runner = mock(AgentRunner.class);
        when(runner.runAs(definition, "hello", TriggerType.MANUAL, "agent-owner")).thenReturn("run-1");
        var service = service(definition, runner, null);
        var request = new TriggerRunRequest();
        request.input = "hello";

        var response = service.trigger(definition.id, request, "agent-owner");

        assertEquals("run-1", response.runId);
        verify(runner).runAs(definition, "hello", TriggerType.MANUAL, "agent-owner");
    }

    @Test
    void synchronousCallThreadsAuthenticatedCallerIntoAgentRunner() {
        var definition = definition();
        var runner = mock(AgentRunner.class);
        when(runner.runAs(definition, "hello", TriggerType.MANUAL, "agent-owner")).thenReturn("run-1");
        var completed = new AgentRun();
        completed.id = "run-1";
        completed.status = RunStatus.COMPLETED;
        completed.output = "done";
        @SuppressWarnings("unchecked")
        MongoCollection<AgentRun> runCollection = mock(MongoCollection.class);
        when(runCollection.get("run-1")).thenReturn(Optional.of(completed));
        var service = service(definition, runner, runCollection);
        var request = new AgentCallRequest();
        request.input = "hello";

        var response = service.call(definition.id, request, "agent-owner");

        assertEquals("done", response.output);
        verify(runner).runAs(definition, "hello", TriggerType.MANUAL, "agent-owner");
    }

    @Test
    void triggerRejectsAnotherUsersDraftAgent() {
        var definition = definition();
        var runner = mock(AgentRunner.class);
        var service = service(definition, runner, null);

        var error = assertThrows(IllegalArgumentException.class,
            () -> service.trigger(definition.id, new TriggerRunRequest(), "caller-1"));

        assertEquals("agent is unavailable", error.getMessage());
        verifyNoInteractions(runner);
    }

    @Test
    void triggerRejectsMissingSkillInOwnersEditableDraft() {
        var definition = definition();
        definition.skillIds = List.of("missing-skill");
        var runner = mock(AgentRunner.class);
        var service = service(definition, runner, null);
        when(service.skillService.resolveAccessibleSkills(List.of("missing-skill"), "agent-owner"))
                .thenThrow(new ForbiddenException("skill is unavailable"));

        var error = assertThrows(ForbiddenException.class,
                () -> service.trigger(definition.id, new TriggerRunRequest(), "agent-owner"));

        assertEquals("skill is unavailable", error.getMessage());
        verifyNoInteractions(runner);
    }

    @Test
    void synchronousCallRejectsAnotherUsersDraftLlmCall() {
        var definition = definition();
        definition.type = DefinitionType.LLM_CALL;
        var runner = mock(AgentRunner.class);
        var service = service(definition, runner, null);

        var error = assertThrows(IllegalArgumentException.class,
            () -> service.call(definition.id, new AgentCallRequest(), "caller-1"));

        assertEquals("agent is unavailable", error.getMessage());
        verifyNoInteractions(runner);
    }

    @Test
    void triggerRunsAnotherUsersPublishedAgentFromDetachedSnapshot() {
        var definition = definition();
        definition.status = AgentStatus.PUBLISHED;
        definition.model = "editable-model";
        definition.inputTemplate = "editable-secret-input";
        definition.skillIds = List.of("editable-private-skill");
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.model = "published-model";
        definition.publishedConfig.inputTemplate = "published-input";
        definition.publishedConfig.skillIds = List.of("frozen-published-skill");
        definition.publishedConfig.skillValidationVersion = 1;
        definition.publishedConfig.sandboxConfig = new AgentSandboxConfig();
        definition.publishedConfig.sandboxConfig.enabled = Boolean.TRUE;
        var runner = mock(AgentRunner.class);
        when(runner.runAs(any(AgentDefinition.class), eq("published-input"), eq(TriggerType.MANUAL), eq("caller-1")))
            .thenReturn("run-1");
        var service = service(definition, runner, null);
        var request = new TriggerRunRequest();

        service.trigger(definition.id, request, "caller-1");

        var definitionCaptor = org.mockito.ArgumentCaptor.forClass(AgentDefinition.class);
        verify(runner).runAs(
            definitionCaptor.capture(), eq("published-input"), eq(TriggerType.MANUAL), eq("caller-1"));
        AgentDefinition executable = definitionCaptor.getValue();
        assertNotSame(definition, executable);
        assertNotSame(definition.publishedConfig, executable.publishedConfig);
        assertEquals("caller-1", executable.userId);
        assertEquals("published-model", executable.publishedConfig.model);
        assertNull(executable.model);
        assertNotSame(definition.publishedConfig.sandboxConfig, executable.sandboxConfig);
        assertEquals(Boolean.TRUE, executable.sandboxConfig.enabled);
        verify(service.skillService, never()).resolveAccessibleSkills(any(), eq("caller-1"));
    }

    @Test
    void triggerRejectsLegacyPublishedSkillsWithoutValidationProvenance() {
        var definition = definition();
        definition.status = AgentStatus.PUBLISHED;
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.skillIds = List.of("historical-skill");
        var runner = mock(AgentRunner.class);
        var service = service(definition, runner, null);

        var error = assertThrows(IllegalArgumentException.class,
            () -> service.trigger(definition.id, new TriggerRunRequest(), "viewer"));

        assertEquals("agent is unavailable", error.getMessage());
        verifyNoInteractions(runner);
        verifyNoInteractions(service.skillService);
    }

    @Test
    void llmCallRejectsLegacyPublishedMutableSystemPromptReference() {
        var definition = definition();
        definition.type = DefinitionType.LLM_CALL;
        definition.status = AgentStatus.PUBLISHED;
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.systemPromptId = "legacy-live-prompt";
        var runner = mock(AgentRunner.class);
        var service = service(definition, runner, null);
        service.llmCallExecutor = mock(LLMCallExecutor.class);

        var error = assertThrows(IllegalArgumentException.class,
            () -> service.llmCall(definition.id, new LLMCallRequest(), "caller-1"));

        assertEquals("LLM call tool is unavailable", error.getMessage());
        verifyNoInteractions(service.llmCallExecutor);
    }

    @SuppressWarnings("unchecked")
    private AgentRunService service(AgentDefinition definition, AgentRunner runner,
                                    MongoCollection<AgentRun> runCollection) {
        MongoCollection<AgentDefinition> definitionCollection = mock(MongoCollection.class);
        when(definitionCollection.get(definition.id)).thenReturn(Optional.of(definition));
        var service = new AgentRunService();
        service.agentRunner = runner;
        service.agentDefinitionCollection = definitionCollection;
        service.agentRunCollection = runCollection;
        service.skillService = mock(SkillService.class);
        service.permissionService = mock(ai.core.server.apiuser.PermissionService.class);
        service.apiUserQuotaService = mock(ai.core.server.apiuser.ApiUserQuotaService.class);
        return service;
    }

    private AgentDefinition definition() {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = "agent-owner";
        definition.type = DefinitionType.AGENT;
        return definition;
    }
}
