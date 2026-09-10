package ai.core.server.agenthub;

import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.Part;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.server.agenthub.AgentHubAttachment;
import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.api.server.agenthub.AgentHubRunRequest;
import ai.core.api.server.run.LLMCallRequest;
import ai.core.api.server.run.LLMCallResponse;
import ai.core.server.a2a.A2ATaskView;
import ai.core.server.a2a.ServerA2ACallerService;
import ai.core.server.agent.AgentCallAccessPolicy;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.run.AgentRunService;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The hub is the CLI's execution contract, so what is asserted here is the wire shape the CLI reads:
 * the status vocabulary, the approval payload of an {@code input_required} task, output truncation,
 * audit rows, and that a rejected or malformed call never reaches the execution layer.
 *
 * @author stephen
 */
class AgentHubServiceTest {
    private static final String USER = "user-1";
    private static final String AGENT_ID = "agent-1";
    private static final String TASK_ID = "task-1";
    private static final String CONTEXT_ID = "ctx-1";

    private AgentCatalogService catalog;
    private AgentCallAccessPolicy accessPolicy;
    private ServerA2ACallerService callerService;
    private AgentRunService agentRunService;
    private HubCallAuditService auditService;
    private AgentHubService hub;

    @BeforeEach
    void setUp() {
        catalog = mock(AgentCatalogService.class);
        accessPolicy = mock(AgentCallAccessPolicy.class);
        callerService = mock(ServerA2ACallerService.class);
        agentRunService = mock(AgentRunService.class);
        auditService = mock(HubCallAuditService.class);
        hub = new AgentHubService();
        hub.catalog = catalog;
        hub.accessPolicy = accessPolicy;
        hub.callerService = callerService;
        hub.agentRunService = agentRunService;
        hub.auditService = auditService;
        when(auditService.begin(any())).thenReturn("audit-1");
        when(catalog.capabilityFor(any(), any())).thenReturn(AgentCatalogService.Capability.empty());
    }

    @Test
    void statusMapsEveryProtocolStateOntoTheCallerContract() {
        assertEquals("completed", statusOf(TaskState.COMPLETED));
        assertEquals("input_required", statusOf(TaskState.INPUT_REQUIRED));
        assertEquals("failed", statusOf(TaskState.FAILED));
        assertEquals("failed", statusOf(TaskState.REJECTED));
        assertEquals("cancelled", statusOf(TaskState.CANCELED));
        assertEquals("running", statusOf(TaskState.SUBMITTED));
        assertEquals("running", statusOf(TaskState.WORKING));
        assertEquals("running", statusOf(TaskState.UNKNOWN));
    }

    @Test
    void taskWithoutProtocolStateIsReportedAsRunning() {
        var task = task(null);
        stubView(new A2ATaskView(task, CONTEXT_ID, null, null, null, null, null, null));

        var result = hub.status(USER, TASK_ID);

        assertEquals("running", result.status);
        assertEquals(TASK_ID, result.taskId);
        assertEquals(CONTEXT_ID, result.contextId);
    }

    @Test
    void inputRequiredCarriesTheApprovalTheCallerHasToAnswer() {
        stubView(new A2ATaskView(task(TaskState.INPUT_REQUIRED), CONTEXT_ID, 10L, 20L,
                "call-1", "shell", "{\"command\":\"ls\"}", null));

        var result = hub.status(USER, TASK_ID);

        assertEquals("input_required", result.status);
        assertEquals(TASK_ID, result.taskId);
        assertEquals(CONTEXT_ID, result.contextId);
        assertNotNull(result.inputRequest);
        assertEquals("call-1", result.inputRequest.callId);
        assertEquals("shell", result.inputRequest.tool);
        assertEquals("{\"command\":\"ls\"}", result.inputRequest.arguments);
        assertTrue(result.inputRequest.message.contains("shell"));
        assertEquals(Map.of("input", 10L, "output", 20L), result.tokenUsage);
    }

    @Test
    void failedTaskKeepsItsProtocolMessageAndGetsTheAgentFailedCode() {
        stubView(new A2ATaskView(task(TaskState.FAILED), CONTEXT_ID, null, null, null, null, null, "boom"));

        var result = hub.status(USER, TASK_ID);

        assertEquals("failed", result.status);
        assertEquals("agent_failed", result.errorCode);
        assertEquals("boom", result.errorMessage);
        assertNull(result.outputTruncated);
    }

    @Test
    void completedTaskHasNoErrorAndJoinsArtifactTexts() {
        var task = task(TaskState.COMPLETED);
        task.artifacts = List.of(artifact("first", "second"), artifact("third"));
        stubView(new A2ATaskView(task, CONTEXT_ID, null, null, null, null, null, null));

        var result = hub.status(USER, TASK_ID);

        assertEquals("completed", result.status);
        assertNull(result.errorCode);
        assertNull(result.errorMessage);
        assertEquals("first\nsecond\nthird", result.output);
        assertEquals(Boolean.FALSE, result.outputTruncated);
    }

    @Test
    void outputBeyondTheDefaultBudgetIsTruncatedAndFlagged() {
        var task = task(TaskState.COMPLETED);
        task.artifacts = List.of(artifact("x".repeat(20001)));
        stubView(new A2ATaskView(task, CONTEXT_ID, null, null, null, null, null, null));

        var result = hub.status(USER, TASK_ID);

        assertEquals(Boolean.TRUE, result.outputTruncated);
        assertEquals(20000, result.output.length());
    }

    @Test
    void runRejectsBlankAndOversizedTasksBeforeTouchingAnything() {
        var agent = catalogAgent(DefinitionType.AGENT);

        var blank = assertThrows(BadRequestException.class, () -> hub.run(USER, agent, request("")));
        assertTrue(blank.getMessage().contains("task required"));

        var oversized = assertThrows(BadRequestException.class, () -> hub.run(USER, agent, request("x".repeat(30001))));
        assertEquals("input_too_long", oversized.errorCode());

        verifyNoInteractions(callerService, agentRunService);
        verify(auditService, never()).begin(any());
    }

    @Test
    void runChecksTheWhitelistAndQuotaBeforeAnythingIsExecutedOrAudited() {
        var agent = catalogAgent(DefinitionType.AGENT);
        doThrow(new IllegalStateException("quota exceeded")).when(accessPolicy).checkCanRun(USER, AGENT_ID);

        assertThrows(IllegalStateException.class, () -> hub.run(USER, agent, request("do it")));

        verifyNoInteractions(callerService, agentRunService);
        verify(auditService, never()).begin(any());
    }

    @Test
    void agentRunCarriesTheTaskTheAttachmentsAndTheContextToTheSession() {
        var agent = catalogAgent(DefinitionType.AGENT);
        when(callerService.sendForCaller(eq(AGENT_ID), any(SendMessageRequest.class), eq(USER), any()))
                .thenReturn(new A2ATaskView(task(TaskState.COMPLETED), CONTEXT_ID, 10L, 20L, null, null, null, null));
        var request = request("review the pr");
        request.contextId = CONTEXT_ID;
        request.attachments = List.of(attachment("image", "https://host/a.png"));

        var result = hub.run(USER, agent, request);

        assertEquals("completed", result.status);
        assertEquals(TASK_ID, result.taskId);
        assertEquals(AGENT_ID, result.agentId);
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(callerService).sendForCaller(eq(AGENT_ID), captor.capture(), eq(USER), any());
        assertEquals("ROLE_USER", captor.getValue().message.role);
        assertEquals(CONTEXT_ID, captor.getValue().message.contextId);
        assertEquals(2, captor.getValue().message.parts.size());
        assertEquals("review the pr", captor.getValue().message.parts.get(0).text);
        assertEquals("image/*", captor.getValue().message.parts.get(1).mediaType);
        assertEquals("https://host/a.png", captor.getValue().message.parts.get(1).url);
        verify(auditService).attachRun("audit-1", TASK_ID, CONTEXT_ID, 10L, 20L);
    }

    @Test
    void aFreshRunHasNoContextSoTheSessionIsCreated() {
        var agent = catalogAgent(DefinitionType.AGENT);
        when(callerService.sendForCaller(eq(AGENT_ID), any(SendMessageRequest.class), eq(USER), any()))
                .thenReturn(new A2ATaskView(task(TaskState.COMPLETED), "ctx-new", null, null, null, null, null, null));

        hub.run(USER, agent, request("start over"));

        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(callerService).sendForCaller(eq(AGENT_ID), captor.capture(), eq(USER), any());
        assertNull(captor.getValue().message.contextId);
    }

    @Test
    void attachmentWithoutUrlIsRejectedAsBadRequest() {
        var agent = catalogAgent(DefinitionType.AGENT);
        var request = request("look");
        request.attachments = List.of(attachment("image", " "));

        var exception = assertThrows(BadRequestException.class, () -> hub.run(USER, agent, request));

        assertTrue(exception.getMessage().contains("attachment url required"));
        verifyNoInteractions(callerService);
    }

    @Test
    void llmCallRunsStatelesslyThroughTheRunService() {
        var agent = catalogAgent(DefinitionType.LLM_CALL);
        var response = new LLMCallResponse();
        response.output = "summary";
        response.tokenUsage = Map.of("input", 5L, "output", 7L);
        when(agentRunService.llmCall(eq(AGENT_ID), any(LLMCallRequest.class), eq(USER))).thenReturn(response);
        var request = request("summarize this");
        request.contextId = "ctx-ignored";
        request.attachments = List.of(attachment("pdf", "https://host/a.pdf"));

        var result = hub.run(USER, agent, request);

        assertEquals("completed", result.status);
        assertEquals("summary", result.output);
        assertEquals(Map.of("input", 5L, "output", 7L), result.tokenUsage);
        assertNull(result.taskId, "an LLM_CALL is stateless: no A2A task");
        assertNull(result.contextId, "an LLM_CALL cannot continue a conversation");
        verifyNoInteractions(callerService);
        var captor = ArgumentCaptor.forClass(LLMCallRequest.class);
        verify(agentRunService).llmCall(eq(AGENT_ID), captor.capture(), eq(USER));
        assertEquals("summarize this", captor.getValue().input);
        assertEquals(LLMCallRequest.AttachmentType.PDF, captor.getValue().attachments.get(0).type);
        verify(auditService).finish(eq("audit-1"), anyLong(), eq("summary"), eq(true), isNull(), isNull());
    }

    @Test
    void replyRequiresADecisionOrAMessage() {
        var empty = assertThrows(BadRequestException.class, () -> hub.reply(USER, TASK_ID, new AgentHubReplyRequest()));

        assertTrue(empty.getMessage().contains("decision or message required"));
        verifyNoInteractions(callerService);
    }

    @Test
    void replyResumesTheTaskWithTheDecision() {
        var request = new AgentHubReplyRequest();
        request.decision = "approve";
        when(callerService.replyForCaller(eq(TASK_ID), eq(USER), eq("approve"), isNull(), any()))
                .thenReturn(new A2ATaskView(task(TaskState.COMPLETED), CONTEXT_ID, null, null, null, null, null, null));

        var result = hub.reply(USER, TASK_ID, request);

        assertEquals("completed", result.status);
        verify(callerService).replyForCaller(eq(TASK_ID), eq(USER), eq("approve"), isNull(), any());
        verify(auditService).begin(any());
    }

    @Test
    void cancelReturnsTheCancelledTask() {
        when(callerService.cancelForCaller(TASK_ID, USER))
                .thenReturn(new A2ATaskView(task(TaskState.CANCELED), CONTEXT_ID, null, null, null, null, null, null));

        var result = hub.cancel(USER, TASK_ID);

        assertEquals("cancelled", result.status);
        assertEquals(TASK_ID, result.taskId);
    }

    @Test
    void searchFiltersOutAgentsTheCallerIsNotWhitelistedFor() {
        when(catalog.search(USER, null, null, null, null)).thenReturn(List.of(catalogAgent(DefinitionType.AGENT)));
        when(accessPolicy.canAccess(USER, AGENT_ID)).thenReturn(Boolean.FALSE);

        assertEquals(List.of(), hub.search(USER, null, null, null, null));

        when(accessPolicy.canAccess(USER, AGENT_ID)).thenReturn(Boolean.TRUE);
        assertEquals(1, hub.search(USER, null, null, null, null).size());
    }

    @Test
    void resolveRejectsAnAgentTheCallerCannotAccess() {
        when(catalog.find(USER, AGENT_ID)).thenReturn(catalogAgent(DefinitionType.AGENT));
        when(accessPolicy.canAccess(USER, AGENT_ID)).thenReturn(Boolean.FALSE);

        assertThrows(NotFoundException.class, () -> hub.resolve(USER, AGENT_ID));
    }

    @Test
    void detailKeepsOnlyTheCapabilitySummaryAndTheLlmCallSchema() {
        var agent = catalogAgent(DefinitionType.LLM_CALL);
        when(catalog.find(USER, AGENT_ID)).thenReturn(agent);
        when(accessPolicy.canAccess(USER, AGENT_ID)).thenReturn(Boolean.TRUE);
        when(catalog.capabilityFor(agent, USER)).thenReturn(new AgentCatalogService.Capability(2, List.of("jira"),
                List.of(), false, "summarize the ticket", "{\"type\":\"object\"}"));

        var detail = hub.get(USER, AGENT_ID);

        assertEquals(2, detail.toolCount);
        assertEquals(List.of("jira"), detail.skillNames);
        assertEquals("summarize the ticket", detail.inputHint);
        assertEquals("{\"type\":\"object\"}", detail.responseSchema);
        assertEquals("llm_call", detail.type);
        assertEquals("server", detail.source);
        assertEquals(Boolean.TRUE, detail.ownerIsMe);
    }

    private void stubView(A2ATaskView view) {
        when(callerService.viewTask(TASK_ID, USER)).thenReturn(view);
    }

    private String statusOf(TaskState state) {
        stubView(new A2ATaskView(task(state), CONTEXT_ID, null, null, null, null, null, null));
        return hub.status(USER, TASK_ID).status;
    }

    private AgentHubRunRequest request(String task) {
        var request = new AgentHubRunRequest();
        request.task = task;
        return request;
    }

    private Task task(TaskState state) {
        var task = new Task();
        task.id = TASK_ID;
        task.contextId = CONTEXT_ID;
        if (state != null) {
            var status = new TaskStatus();
            status.state = state;
            task.status = status;
        }
        return task;
    }

    private Artifact artifact(String... texts) {
        var artifact = new Artifact();
        artifact.parts = Arrays.stream(texts).map(Part::text).toList();
        return artifact;
    }

    private AgentCatalogService.CatalogAgent catalogAgent(DefinitionType type) {
        var definition = new AgentDefinition();
        definition.id = AGENT_ID;
        definition.name = "reviewer";
        definition.userId = USER;
        definition.type = type;
        return new AgentCatalogService.CatalogAgent(definition, AgentCatalogService.Capability.empty(),
                AgentCatalogService.Capability.empty());
    }

    private AgentHubAttachment attachment(String type, String url) {
        var attachment = new AgentHubAttachment();
        attachment.type = type;
        attachment.url = url;
        return attachment;
    }
}
