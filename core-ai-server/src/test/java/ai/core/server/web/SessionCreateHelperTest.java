package ai.core.server.web;

import ai.core.api.server.session.CreateSessionRequest;
import ai.core.api.server.tool.ToolRefView;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.tool.ToolRegistryService;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionCreateHelperTest {
    @Test
    void loadToolsOnSessionCreateReturnsOriginalToolRefIds() {
        var helper = new SessionCreateHelper();
        helper.sessionManager = mock(AgentSessionManager.class);
        helper.toolRegistryService = mock(ToolRegistryService.class);
        var ref = toolRef("mcp-1", ToolSourceType.MCP);
        when(helper.sessionManager.loadToolRefs(eq("s-1"), eq(List.of(ref)))).thenReturn(List.of(ref));
        var registry = new ToolRegistry();
        registry.id = "mcp-1";
        registry.name = "Weather";
        registry.type = ToolType.MCP;
        when(helper.toolRegistryService.getTool("mcp-1")).thenReturn(registry);

        var loaded = helper.loadToolsOnSessionCreate("s-1", request("mcp-1", "MCP"));

        assertEquals(1, loaded.size());
        assertEquals("mcp-1", loaded.getFirst().id);
        assertEquals("Weather", loaded.getFirst().name);
    }

    @Test
    void loadToolsOnSessionCreatePropagatesUnresolvedRefs() {
        var helper = new SessionCreateHelper();
        helper.sessionManager = mock(AgentSessionManager.class);
        var ref = toolRef("missing-mcp", ToolSourceType.MCP);
        when(helper.sessionManager.loadToolRefs(eq("s-1"), eq(List.of(ref))))
                .thenThrow(new NotFoundException("no tools found for refs: " + List.of(ref)));

        assertThrows(NotFoundException.class, () -> helper.loadToolsOnSessionCreate("s-1", request("missing-mcp", "MCP")));
    }

    private CreateSessionRequest request(String id, String type) {
        var tool = new ToolRefView();
        tool.id = id;
        tool.type = type;
        var request = new CreateSessionRequest();
        request.tools = List.of(tool);
        return request;
    }

    private ToolRef toolRef(String id, ToolSourceType type) {
        var ref = new ToolRef();
        ref.id = id;
        ref.type = type;
        return ref;
    }
}
