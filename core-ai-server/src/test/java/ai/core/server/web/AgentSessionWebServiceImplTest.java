package ai.core.server.web;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.session.ChatMessageService;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionWebServiceImplTest {
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
}
