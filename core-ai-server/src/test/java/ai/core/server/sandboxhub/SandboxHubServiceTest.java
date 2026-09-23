package ai.core.server.sandboxhub;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.server.hub.HubCallAuditService;
import ai.core.server.session.AgentSessionManager;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ToolExposure;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolRegistry;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The hub executes through the session's own agent, so what this test pins down is the mapping:
 * which tool the call resolved to, how the result is shaped for the script, and what the audit row
 * carries (the sandbox and the session, so {@code hub_calls} answers "which script asked for this").
 *
 * @author xander
 */
class SandboxHubServiceTest {
    private static final String SESSION_ID = "s1";
    private static final String SANDBOX_ID = "sb1";
    private static final String AUDIT_ID = "call-1";

    private static ToolCall hubTool(String rawName) {
        return tool(rawName, ToolExposure.DIRECT);
    }

    private static ToolCall tool(String name, ToolExposure exposure) {
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed(arguments);
            }
        };
        tool.setName(name);
        tool.setParameters(new ArrayList<>());
        tool.setExposure(exposure);
        return tool;
    }

    private SandboxHubService service;
    private AgentSessionManager sessionManager;
    private HubCallAuditService auditService;
    private RunAgentRegistry runAgents;
    private Agent agent;

    @BeforeEach
    void setUp() {
        service = new SandboxHubService();
        sessionManager = mock(AgentSessionManager.class);
        auditService = mock(HubCallAuditService.class);
        runAgents = new RunAgentRegistry();
        agent = mock(Agent.class);
        service.sessionManager = sessionManager;
        service.auditService = auditService;
        service.runAgents = runAgents;
        when(auditService.begin(any())).thenReturn(AUDIT_ID);
    }

    @Test
    void completedToolResultIsAuditedWithSandboxAndSession() {
        bindSession(hubTool("search"));
        when(agent.executeToolOutsideTurn(any(), any(), any())).thenReturn(ToolCallResult.completed("menu found"));

        var response = service.call(session(), "menu_hub_search", request("{}"));

        assertEquals("completed", response.status);
        assertEquals(Boolean.TRUE, response.success);
        assertEquals(Boolean.FALSE, response.isError);
        assertEquals("menu found", response.text);
        assertEquals(AUDIT_ID, response.callId);
        assertTrue(response.content.isEmpty());
        var audit = audit();
        assertEquals(HubCallAuditService.KIND_SANDBOX_TOOL, audit.kind());
        assertEquals("sandbox", audit.source());
        assertEquals("u1", audit.userId());
        assertEquals(SESSION_ID, audit.sessionId());
        assertEquals(SESSION_ID, audit.group());
        assertEquals(SANDBOX_ID, audit.sandboxId());
        assertEquals("mcp", audit.toolKind());
        assertEquals("mcp:menu-hub:search", audit.refId());
        assertEquals("menu_hub_search", audit.name());
        verify(auditService).finish(eq(AUDIT_ID), anyLong(), eq("menu found"), eq(true), eq(null), eq(null));
    }

    @Test
    void failedToolResultIsReportedAsFailed() {
        bindSession(hubTool("search"));
        when(agent.executeToolOutsideTurn(any(), any(), any())).thenReturn(ToolCallResult.failed("server down"));

        var response = service.call(session(), "menu_hub_search", null);

        assertEquals("failed", response.status);
        assertEquals(Boolean.FALSE, response.success);
        assertEquals(Boolean.TRUE, response.isError);
        assertEquals("server down", response.errorMessage);
        verify(auditService).finish(eq(AUDIT_ID), anyLong(), eq("server down"), eq(false), eq(null), eq("server down"));
    }

    @Test
    void toolThatLaunchesWorkReturnsPendingAndIsPollableByTaskId() {
        bindSession(hubTool("search"));
        when(agent.executeToolOutsideTurn(any(), any(), any())).thenReturn(ToolCallResult.asyncLaunched("task-7", "started"));

        var response = service.call(session(), "menu_hub_search", null);

        assertEquals("pending", response.status);
        assertEquals("task-7", response.taskId);

        var polled = service.task(session(), AUDIT_ID);

        assertEquals("pending", polled.status);
        assertEquals("task-7", polled.taskId);
        assertEquals("started", polled.text);
    }

    @Test
    void toolFailureIsReportedAndAuditedAsFailed() {
        bindSession(hubTool("search"));
        when(agent.executeToolOutsideTurn(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        var response = service.call(session(), "menu_hub_search", null);

        assertEquals("failed", response.status);
        assertEquals("boom", response.errorMessage);
        verify(auditService).finish(eq(AUDIT_ID), anyLong(), eq(null), eq(false), eq(null), eq("boom"));
    }

    @Test
    void unknownToolAndHiddenToolAreRejectedBeforeAnyExecution() {
        bindSession(hubTool("search"));

        assertThrows(NotFoundException.class, () -> service.call(session(), "missing", null));

        var hidden = tool("hidden_tool", ToolExposure.HIDDEN);
        bindSession(hubTool("search"), hidden);

        assertThrows(BadRequestException.class, () -> service.call(session(), "hidden_tool", null));
    }

    @Test
    void unknownTaskIsNotFound() {
        bindSession(hubTool("search"));

        assertThrows(NotFoundException.class, () -> service.task(session(), "ghost-task"));
    }

    @Test
    void sessionMissingOnThisServerIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.call(session(), "menu_hub_search", null));
    }

    @Test
    void runAgentIsServedWithoutASession() {
        bindRunAgent(hubTool("search"));
        when(agent.executeToolOutsideTurn(any(), any(), any())).thenReturn(ToolCallResult.completed("menu found"));
        when(agent.getName()).thenReturn("gbp-content-agent");

        var snapshot = service.snapshot(session());

        assertEquals("gbp-content-agent", snapshot.agentName);
        assertEquals("completed", service.call(session(), "menu_hub_search", request("{}")).status);
        verifyNoInteractions(sessionManager);
    }

    @Test
    void sessionServesTheHubWhenNoRunAgentIsRegistered() {
        bindSession(hubTool("search"));
        when(agent.executeToolOutsideTurn(any(), any(), any())).thenReturn(ToolCallResult.completed("from session"));

        var response = service.call(session(), "menu_hub_search", request("{}"));

        assertEquals("from session", response.text);
        verify(sessionManager).getSession(SESSION_ID);
    }

    @Test
    void snapshotCarriesAgentNameAndToolDetails() {
        bindSession(hubTool("search"));
        when(agent.getName()).thenReturn("menu-agent");

        var snapshot = service.snapshot(session());

        assertEquals("menu-agent", snapshot.agentName);
        assertEquals(1, snapshot.detailsOrEmpty().size());
        assertEquals("menu_hub_search", snapshot.detailsOrEmpty().getFirst().name);
        assertEquals("mcp", snapshot.detailsOrEmpty().getFirst().kind);
        assertEquals(120, snapshot.detailsOrEmpty().getFirst().timeoutSeconds);
    }

    @Test
    void callerTimeoutIsClampedToTheHubRange() {
        bindSession(hubTool("search"));
        when(agent.executeToolOutsideTurn(any(), any(), any())).thenReturn(ToolCallResult.completed("ok"));

        var belowFloor = service.call(session(), "menu_hub_search", request("{}", 1));
        var aboveCeiling = service.call(session(), "menu_hub_search", request("{}", 10_000));

        // the wait is clamped, the call itself still succeeds on both ends of the range
        assertEquals("completed", belowFloor.status);
        assertEquals("completed", aboveCeiling.status);
        verify(agent, times(2)).executeToolOutsideTurn(any(), any(), any());
    }

    private HubCallRequest request(String arguments) {
        return request(arguments, null);
    }

    private HubCallRequest request(String arguments, Integer timeoutSeconds) {
        var request = new HubCallRequest();
        request.arguments = arguments;
        request.timeoutSeconds = timeoutSeconds;
        return request;
    }

    private HubCallAuditService.BeginRequest audit() {
        var captor = ArgumentCaptor.forClass(HubCallAuditService.BeginRequest.class);
        verify(auditService).begin(captor.capture());
        return captor.getValue();
    }

    private SandboxHubService.SandboxHubSession session() {
        return new SandboxHubService.SandboxHubSession(SESSION_ID, "u1", SANDBOX_ID, "2026-09-15T00:00:00Z");
    }

    private void bindSession(ToolCall... tools) {
        var registry = new ToolRegistry();
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tool : tools) {
            map.put(tool.getName(), tool);
        }
        registry.registerProvider(new ToolProvider() {
            @Override
            public String id() {
                return "mcp:menu-hub";
            }

            @Override
            public Map<String, ToolCall> provide() {
                return map;
            }
        });
        var context = ExecutionContext.builder().build();
        context.setToolRegistry(registry);
        when(agent.getExecutionContext()).thenReturn(context);
        var agentSession = mock(InProcessAgentSession.class);
        when(agentSession.agent()).thenReturn(agent);
        when(sessionManager.getSession(SESSION_ID)).thenReturn(agentSession);
    }

    /** A run has no session: the runner publishes its agent under the run id instead. */
    private void bindRunAgent(ToolCall... tools) {
        bindSession(tools);
        runAgents.register(SESSION_ID, agent);
    }
}
