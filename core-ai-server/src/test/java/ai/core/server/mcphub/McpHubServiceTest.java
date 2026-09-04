package ai.core.server.mcphub;

import ai.core.api.server.mcphub.HubCallRequest;
import ai.core.api.server.mcphub.HubCallResponse;
import ai.core.server.domain.McpHubCall;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.mcphub.McpToolCatalogService.CatalogTool;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCallResult;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpHubServiceTest {
    private McpToolCatalogService catalog;
    private MongoCollection<McpHubCall> callCollection;
    private ToolRegistryService registry;
    private McpHubService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        catalog = mock(McpToolCatalogService.class);
        var accessPolicy = mock(McpHubAccessPolicy.class);
        callCollection = mock(MongoCollection.class);
        registry = mock(ToolRegistryService.class);
        service = new McpHubService();
        service.catalog = catalog;
        service.accessPolicy = accessPolicy;
        service.callCollection = callCollection;
        service.toolRegistryService = registry;
    }

    @Test
    void describeReturnsSchemaTextAndState() {
        when(catalog.entryByName("jira")).thenReturn(entry());
        when(catalog.findTool("jira", "create_issue"))
                .thenReturn(new CatalogTool("srv-jira", "jira", "create_issue", "Create a Jira issue", "{\"type\":\"object\"}", false));
        when(registry.getMcpServerState("srv-jira")).thenReturn(ai.core.mcp.client.McpClientManager.ConnectionState.CONNECTED);

        var detail = service.describe("jira", "create_issue");

        assertEquals("jira/create_issue", detail.qualifiedName);
        assertEquals("mcp-tool:srv-jira:create_issue", detail.refId);
        assertEquals("{\"type\":\"object\"}", detail.inputSchema);
        assertEquals("CONNECTED", detail.serverState);
    }

    @Test
    void describeUnknownServerOrToolIsNotFound() {
        when(catalog.entryByName("jira")).thenReturn(null);
        assertThrows(NotFoundException.class, () -> service.describe("jira", "x"));
        when(catalog.entryByName("jira")).thenReturn(entry());
        when(catalog.findTool("jira", "x")).thenReturn(null);
        assertThrows(NotFoundException.class, () -> service.describe("jira", "x"));
    }

    @Test
    void callMapsCompletedToolResultToSuccessResponse() {
        stubCall(ToolCallResult.completed("CORE-1234 created"));
        when(registry.getMcpServerState("srv-jira")).thenReturn(ai.core.mcp.client.McpClientManager.ConnectionState.CONNECTED);

        HubCallResponse response = service.call("user-1", "cli", "jira", "create_issue", request("{\"project\":\"CORE\"}", null));

        assertTrue(response.success);
        assertFalse(response.isError);
        assertEquals("CORE-1234 created", response.text);
        assertEquals("CONNECTED", response.serverState);
        verify(callCollection).insert(any(McpHubCall.class));
    }

    @Test
    void callMapsFailedToolResultToBusinessError() {
        stubCall(ToolCallResult.failed("upstream rejected request"));
        when(registry.getMcpServerState("srv-jira")).thenReturn(ai.core.mcp.client.McpClientManager.ConnectionState.CONNECTED);

        HubCallResponse response = service.call("user-1", "cli", "jira", "create_issue", request("{}", null));

        assertFalse(response.success);
        assertTrue(response.isError);
        assertTrue(response.text.contains("upstream rejected"));
    }

    @Test
    void callRejectsMalformedArguments() {
        when(catalog.entryByName("jira")).thenReturn(entry());
        when(catalog.findTool("jira", "create_issue")).thenReturn(tool());
        assertThrows(BadRequestException.class,
                () -> service.call("user-1", "cli", "jira", "create_issue", request("{not json", null)));
    }

    @Test
    void callRejectsOutOfRangeTimeout() {
        when(catalog.entryByName("jira")).thenReturn(entry());
        when(catalog.findTool("jira", "create_issue")).thenReturn(tool());
        assertThrows(BadRequestException.class,
                () -> service.call("user-1", "cli", "jira", "create_issue", request("{}", 999)));
    }

    private void stubCall(ToolCallResult result) {
        when(catalog.entryByName("jira")).thenReturn(entry());
        when(catalog.findTool("jira", "create_issue")).thenReturn(tool());
        when(registry.callMcpServerTool(eq("srv-jira"), eq("create_issue"), any(String.class))).thenReturn(result);
    }

    private CatalogTool tool() {
        return new CatalogTool("srv-jira", "jira", "create_issue", "Create a Jira issue", null, false);
    }

    private HubCallRequest request(String arguments, Integer timeoutSeconds) {
        var request = new HubCallRequest();
        request.arguments = arguments;
        request.timeoutSeconds = timeoutSeconds;
        return request;
    }

    private ToolRegistryEntry entry() {
        var entry = new ToolRegistryEntry();
        entry.id = "srv-jira";
        entry.name = "jira";
        entry.type = ToolType.MCP;
        entry.config = java.util.Map.of();
        entry.enabled = Boolean.TRUE;
        entry.createdAt = ZonedDateTime.now();
        return entry;
    }
}
