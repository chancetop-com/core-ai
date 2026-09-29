package ai.core.server.cataloghub;

import ai.core.server.agenthub.AgentCatalogService;
import ai.core.server.apitoolhub.ApiToolCatalogService;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.mcphub.McpToolCatalogService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.session.SessionIdentity;
import core.framework.web.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HubCatalogServiceTest {
    private McpToolCatalogService mcpCatalog;
    private ApiToolCatalogService apiCatalog;
    private AgentCatalogService agentCatalog;
    private SessionIdentity sessionIdentity;
    private PermissionService permissionService;
    private HubCatalogService service;

    @BeforeEach
    void setUp() {
        mcpCatalog = mock(McpToolCatalogService.class);
        apiCatalog = mock(ApiToolCatalogService.class);
        agentCatalog = mock(AgentCatalogService.class);
        sessionIdentity = mock(SessionIdentity.class);
        permissionService = mock(PermissionService.class);
        var webContext = mock(WebContext.class);
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("caller-1");

        service = new HubCatalogService();
        service.mcpCatalog = mcpCatalog;
        service.apiCatalog = apiCatalog;
        service.agentCatalog = agentCatalog;
        service.sessionIdentity = sessionIdentity;
        service.permissionService = permissionService;
        service.webContext = webContext;
    }

    private void granted(String... permissions) {
        var granted = List.of(permissions);
        when(permissionService.has("caller-1", PermissionCodes.MCP_CALL)).thenReturn(granted.contains(PermissionCodes.MCP_CALL));
        when(permissionService.has("caller-1", PermissionCodes.APITOOL_CALL)).thenReturn(granted.contains(PermissionCodes.APITOOL_CALL));
        when(permissionService.has("caller-1", PermissionCodes.CHAT_USE)).thenReturn(granted.contains(PermissionCodes.CHAT_USE));
    }

    @Test
    void catalogMapsMcpServersAndTools() {
        granted(PermissionCodes.MCP_CALL);
        var entry = new ToolRegistryEntry();
        entry.name = "kubernetes";
        when(mcpCatalog.listServers()).thenReturn(List.of(
                new McpToolCatalogService.ServerViewData(entry, "CONNECTED", 13, false)));
        when(mcpCatalog.allTools()).thenReturn(List.of(
                new McpToolCatalogService.CatalogTool("sid-1", "kubernetes", "pods_list", "List pods.", "{}", false)));

        var response = service.catalog();

        assertNotNull(response.generatedAt);
        assertEquals(List.of("mcp"), response.sections);
        var tool = response.tools.getFirst();
        assertEquals("mcp", tool.kind);
        assertEquals("pods_list", tool.name);
        assertEquals("kubernetes/pods_list", tool.path);
        assertEquals("kubernetes", tool.group);
        assertEquals("mcp-tool:sid-1:pods_list", tool.refId);
        assertEquals("List pods.", tool.description);
        assertFalse(tool.stale);
        var source = response.sources.getFirst();
        assertEquals("mcp", source.kind);
        assertEquals("kubernetes", source.name);
        assertEquals("CONNECTED", source.state);
        assertEquals(13, source.count.intValue());
        assertFalse(source.stale);
    }

    @Test
    void catalogMapsApiAppsAndOperations() {
        granted(PermissionCodes.APITOOL_CALL);
        when(apiCatalog.apps()).thenReturn(List.of(
                new ApiToolCatalogService.AppSummary("order-service", "https://orders", "1", "Orders", 3, 55)));
        when(apiCatalog.allOperations()).thenReturn(List.of(
                new ApiToolCatalogService.CatalogOperation("order-service", "orders", "get_order", "order_service_orders_get_order",
                        "api-operation:order-service:orders:get_order", "Read an order.", "GET", "/orders/:id",
                        Boolean.TRUE, Boolean.FALSE,
                        null, null, null, "{}", "{}")));

        var response = service.catalog();

        var tool = response.tools.getFirst();
        assertEquals("api", tool.kind);
        assertEquals("get_order", tool.name);
        assertEquals("order-service/orders/get_order", tool.path);
        assertEquals("order-service", tool.group);
        assertEquals("api-operation:order-service:orders:get_order", tool.refId);
        assertEquals(55, response.sources.getFirst().count.intValue());
        assertNull(tool.stale);
    }

    @Test
    void catalogMapsAgentsAndLlmCalls() {
        granted(PermissionCodes.CHAT_USE);
        when(agentCatalog.catalog("caller-1")).thenReturn(List.of(
                catalogAgent("id-agent", "review-responder", DefinitionType.AGENT),
                catalogAgent("id-llm", "seo-title-semantics", DefinitionType.LLM_CALL)));

        var response = service.catalog();

        assertEquals(List.of("agent", "llm_call"), response.tools.stream().map(tool -> tool.kind).toList());
        assertEquals(List.of("agent", "llm_call"), response.sections);
        var llmCall = response.tools.get(1);
        assertEquals("seo-title-semantics", llmCall.name);
        assertEquals("seo-title-semantics", llmCall.path);
        assertEquals("id-llm", llmCall.refId);
        assertEquals("seo-title-semantics description", llmCall.description);
        assertNull(llmCall.group);
        assertTrue(response.sources.isEmpty());
    }

    @Test
    void aSectionTheCallerCannotReadIsNeverTouched() {
        granted(PermissionCodes.MCP_CALL);
        when(mcpCatalog.listServers()).thenReturn(List.of());
        when(mcpCatalog.allTools()).thenReturn(List.of());

        var response = service.catalog();

        assertTrue(response.tools.isEmpty());
        assertTrue(response.sources.isEmpty());
        assertEquals(List.of("mcp"), response.sections);
        verify(apiCatalog, never()).allOperations();
        verify(agentCatalog, never()).catalog("caller-1");
    }

    @Test
    void aPermissionFromTheSessionIdentityIsEnough() {
        when(sessionIdentity.hasAny(PermissionCodes.MCP_CALL)).thenReturn(Boolean.TRUE);
        when(mcpCatalog.listServers()).thenReturn(List.of());
        when(mcpCatalog.allTools()).thenReturn(List.of());

        var response = service.catalog();

        assertTrue(response.tools.isEmpty());
        verify(mcpCatalog).allTools();
        verify(apiCatalog, never()).allOperations();
    }

    private AgentCatalogService.CatalogAgent catalogAgent(String id, String name, DefinitionType type) {
        var definition = new AgentDefinition();
        definition.id = id;
        definition.name = name;
        definition.description = name + " description";
        definition.type = type;
        return new AgentCatalogService.CatalogAgent(definition, null, null);
    }
}
