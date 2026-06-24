package ai.core.server.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolRefTest {
    @Test
    void infersServiceApiRefTypes() {
        assertApi("api-app:core");
        assertApi("api-service:core:UserService");
        assertApi("api-operation:core:UserService:getUser");
        assertApi("builtin-service-api");
    }

    // Config-file MCP servers use a colon-prefixed id (config:{name}), so the serverId itself
    // contains a colon. The tool ref must keep the full prefix instead of splitting at the first colon.
    @Test
    void parsesMcpToolIdForConfigServer() {
        var parsed = ToolRef.parseMcpToolId("mcp-tool:config:weather:get_forecast", null);
        assertEquals("config:weather", parsed.serverId());
        assertEquals("get_forecast", parsed.toolName());
    }

    @Test
    void parsesMcpToolIdForDatabaseServer() {
        var parsed = ToolRef.parseMcpToolId("mcp-tool:66a8c3e9f4b2d1e9a7c2b3d4:get_forecast", null);
        assertEquals("66a8c3e9f4b2d1e9a7c2b3d4", parsed.serverId());
        assertEquals("get_forecast", parsed.toolName());
    }

    @Test
    void parsesMcpToolIdWithServerFromSource() {
        var parsed = ToolRef.parseMcpToolId("mcp-tool:get_forecast", "config:weather");
        assertEquals("config:weather", parsed.serverId());
        assertEquals("get_forecast", parsed.toolName());
    }

    @Test
    void parseMcpToolIdReturnsNullForNonMcpId() {
        assertNull(ToolRef.parseMcpToolId("config:weather", null));
        assertNull(ToolRef.parseMcpToolId(null, null));
    }

    private void assertApi(String id) {
        var ref = new ToolRef();
        ref.id = id;
        ref.inferTypeFromId();
        assertEquals(ToolSourceType.API, ref.type);
    }
}
