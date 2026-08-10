package ai.core.server.web;

import ai.core.api.server.session.LoadSkillsRequest;
import ai.core.api.server.session.CreateSessionRequest;
import ai.core.api.server.session.SessionConfig;
import ai.core.api.server.session.UnloadSkillsRequest;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.SessionState;
import ai.core.server.web.auth.AuthContext;
import ai.core.session.InProcessAgentSession;
import core.framework.web.Request;
import core.framework.web.Session;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionWebServiceImplTest {
    @Test
    void createCompensatesWhenPostCreateInitializationFails() {
        var service = createService();
        var request = new CreateSessionRequest();
        request.config = new SessionConfig();
        when(service.sessionManager.createSession(request.config, "user-1", "chat", null))
                .thenReturn("s-1");
        doThrow(new IllegalStateException("tool initialization failed"))
                .when(service.createHelper).loadToolsOnSessionCreate("s-1", request, "user-1");

        var error = assertThrows(IllegalStateException.class, () -> service.create(request));

        assertEquals("tool initialization failed", error.getMessage());
        verify(service.sessionManager).abortSessionCreation("s-1");
    }

    @Test
    void createDoesNotCompensateWhenNoSessionIdWasCreated() {
        var service = createService();
        var request = new CreateSessionRequest();
        when(service.sessionManager.createSession(null, "user-1", "chat", null))
                .thenThrow(new IllegalStateException("creation failed"));

        assertThrows(IllegalStateException.class, () -> service.create(request));

        verify(service.sessionManager, never()).abortSessionCreation(any());
    }

    @Test
    void getInfoReturnsPersistedLoadedResources() {
        var service = new AgentSessionWebServiceImpl();
        service.webContext = mock(WebContext.class);
        service.chatMessageService = mock(ChatMessageService.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");

        var session = new ChatSession();
        session.id = "s-1";
        session.userId = "user-1";
        session.agentId = "agent-1";
        session.loadedTools = List.of(ToolRef.of("mcp-tool:server:search", ToolSourceType.MCP, "server"));
        session.loadedSkillIds = List.of(" skill-1 ", "", "skill-1", "skill-2");
        session.loadedSubAgentIds = List.of("sub-1", "sub-1", " sub-2 ");
        when(service.chatMessageService.getSessionMeta("s-1")).thenReturn(session);

        var info = service.getInfo("s-1");

        assertEquals("s-1", info.id);
        assertEquals("agent-1", info.agentId);
        assertEquals(1, info.loadedTools.size());
        assertEquals("mcp-tool:server:search", info.loadedTools.getFirst().id);
        assertEquals("MCP", info.loadedTools.getFirst().type);
        assertEquals("server", info.loadedTools.getFirst().source);
        assertEquals(List.of("skill-1", "skill-2"), info.loadedSkillIds);
        assertEquals(List.of("sub-1", "sub-2"), info.loadedSubAgentIds);
    }

    @Test
    void historyRejectsSessionsOwnedByAnotherUser() {
        var service = new AgentSessionWebServiceImpl();
        service.webContext = mock(WebContext.class);
        service.chatMessageService = mock(ChatMessageService.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-2");

        var session = new ChatSession();
        session.id = "s-1";
        session.userId = "user-1";
        when(service.chatMessageService.getSessionMeta("s-1")).thenReturn(session);

        assertThrows(ForbiddenException.class, () -> service.history("s-1"));
        verify(service.chatMessageService, never()).history("s-1");
    }

    @Test
    void localDynamicSkillMutationPreservesCallerIdentity() {
        var service = new AgentSessionWebServiceImpl();
        service.webContext = mock(WebContext.class);
        service.sessionManager = mock(AgentSessionManager.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("caller-1");
        when(service.sessionManager.loadSkills("s-1", List.of("skill-1"), "caller-1"))
                .thenReturn(List.of("Admin/skill-1"));
        when(service.sessionManager.unloadSkills("s-1", List.of("skill-1"), "caller-1"))
                .thenReturn(List.of());
        var loadRequest = new LoadSkillsRequest();
        loadRequest.skillIds = List.of("skill-1");
        var unloadRequest = new UnloadSkillsRequest();
        unloadRequest.skillIds = List.of("skill-1");

        service.loadSkills("s-1", loadRequest);
        service.unloadSkills("s-1", unloadRequest);

        verify(service.sessionManager).loadSkills("s-1", List.of("skill-1"), "caller-1");
        verify(service.sessionManager).unloadSkills("s-1", List.of("skill-1"), "caller-1");
    }

    @Test
    void localLegacySnapshotRebuildPreservesAuthenticatedCallerIdentity() {
        var service = new AgentSessionWebServiceImpl();
        service.webContext = mock(WebContext.class);
        service.sessionManager = mock(AgentSessionManager.class);
        service.agentDraftGenerator = mock(AgentDraftGenerator.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("caller-1");
        var request = mock(Request.class);
        var httpSession = mock(Session.class);
        when(service.webContext.request()).thenReturn(request);
        when(request.session()).thenReturn(httpSession);
        var legacyState = new SessionState();
        legacyState.fromAgent = true;
        legacyState.agentConfig = new SessionState.AgentConfigSnapshot();
        legacyState.agentConfig.agentId = "agent-1";
        when(httpSession.get("agent-session-state:s-1"))
                .thenReturn(Optional.of(legacyState.toJson()));
        var agentSession = mock(InProcessAgentSession.class);
        when(service.sessionManager.getSession(org.mockito.ArgumentMatchers.eq("s-1"),
                argThat(state -> state != null && state.agentConfig != null
                        && "agent-1".equals(state.agentConfig.agentId)),
                org.mockito.ArgumentMatchers.eq("caller-1")))
                .thenReturn(agentSession);

        service.generateAgentDraft("s-1");

        verify(service.sessionManager).getSession(org.mockito.ArgumentMatchers.eq("s-1"),
                argThat(state -> state != null && state.agentConfig != null
                        && "agent-1".equals(state.agentConfig.agentId)),
                org.mockito.ArgumentMatchers.eq("caller-1"));
    }

    private AgentSessionWebServiceImpl createService() {
        var service = new AgentSessionWebServiceImpl();
        service.webContext = mock(WebContext.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        service.sessionManager = mock(AgentSessionManager.class);
        service.createHelper = mock(SessionCreateHelper.class);
        service.permissionService = mock(PermissionService.class);
        service.apiUserQuotaService = mock(ApiUserQuotaService.class);
        return service;
    }
}
