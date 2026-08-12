package ai.core.server.web;

import ai.core.mcp.client.McpClientManager;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.tool.ToolRegistryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryWebServiceImplTest {
    @Test
    void failedMcpStatusExplainsHowToRetryWithoutExposingInternalErrors() {
        var registry = mock(ToolRegistryService.class);
        var entity = new ToolRegistryEntry();
        entity.enabled = Boolean.TRUE;
        when(registry.getTool("meta-ads")).thenReturn(entity);
        when(registry.getMcpServerState("meta-ads")).thenReturn(McpClientManager.ConnectionState.FAILED);
        var service = new ToolRegistryWebServiceImpl();
        service.toolRegistryService = registry;

        var response = service.getMcpServerStatus("meta-ads");

        assertEquals("Connection failed. Check the server URL and credentials, save changes, then retry.", response.message);
    }
}
