package ai.core.server.tool;

import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.mcp.client.McpClientManager;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpServerOperationServiceTest {
    @SuppressWarnings("unchecked")
    private static MongoCollection<ToolRegistryEntry> collection() {
        return (MongoCollection<ToolRegistryEntry>) mock(MongoCollection.class);
    }

    private final MongoCollection<ToolRegistryEntry> collection = collection();
    private final McpServerConnectionManager connectionManager = mock(McpServerConnectionManager.class);
    private final ApplicationMcpManager applicationMcpManager = mock(ApplicationMcpManager.class);
    private final Map<String, ToolRegistryEntry> tools = new HashMap<>();
    private McpServerOperationService service;

    @BeforeEach
    void setUp() {
        service = new McpServerOperationService(
            tools,
            connectionManager,
            applicationMcpManager
        );
        service.setToolRegistryCollection(collection);
    }

    @Test
    void importsCommandServerAsSandboxHostedWithoutChangingNestedValues() {
        var created = service.importMcpServers("""
            {"mcpServers":{"local-tools":{"command":"npx","args":["-y","@scope/server"],"env":{"API_TOKEN":"secret"}}}}
            """, null, "development", Boolean.FALSE);

        assertEquals(1, created.size());
        var config = created.getFirst().config;
        assertEquals("sandbox_hosted", config.get("transport"));
        assertEquals("npx", config.get("command"));
        assertEquals("[\"-y\",\"@scope/server\"]", config.get("args"));
        assertEquals("{\"API_TOKEN\":\"secret\"}", config.get("env"));
    }

    @Test
    void importsRemoteHttpServerAndPreservesHeadersAndEndpoint() {
        var created = service.importMcpServers("""
            {"mcpServers":{"meta-ads":{"url":"https://mcp.facebook.com","endpoint":"/ads","headers":{"Authorization":"Bearer secret"}}}}
            """, null, null, Boolean.FALSE);

        assertEquals(1, created.size());
        var config = created.getFirst().config;
        assertEquals("https://mcp.facebook.com", config.get("url"));
        assertEquals("/ads", config.get("endpoint"));
        assertEquals("{\"Authorization\":\"Bearer secret\"}", config.get("headers"));
        assertFalse(config.containsKey("transport"));
    }

    @Test
    void importsSingleServerConfigNamedByTheRequest() {
        var created = service.importMcpServers("""
            {"command":"uvx","args":["mcp-atlassian"],"env":{"JIRA_API_TOKEN":"secret"}}
            """, "mcp-atlassian", "development", Boolean.FALSE);

        assertEquals(1, created.size());
        var entity = created.getFirst();
        assertEquals("mcp-atlassian", entity.name);
        assertEquals("sandbox_hosted", entity.config.get("transport"));
        assertEquals("uvx", entity.config.get("command"));
        assertEquals("[\"mcp-atlassian\"]", entity.config.get("args"));
        assertTrue(entity.rawConfig.contains("\"command\":\"uvx\""));
    }

    @Test
    void importsSingleServerConfigUsingDeclaredNameAndDropsItFromConfig() {
        var created = service.importMcpServers("""
            {"name":"mcp-atlassian","command":"uvx","args":["mcp-atlassian"]}
            """, null, null, Boolean.FALSE);

        assertEquals(1, created.size());
        var entity = created.getFirst();
        assertEquals("mcp-atlassian", entity.name);
        assertFalse(entity.config.containsKey("name"));
    }

    @Test
    void prefersRequestNameOverDeclaredNameForSingleServerConfig() {
        var created = service.importMcpServers("""
            {"name":"declared","url":"https://example.com/mcp"}
            """, "requested", null, Boolean.FALSE);

        assertEquals(1, created.size());
        assertEquals("requested", created.getFirst().name);
    }

    @Test
    void renamesSingleServerImportedFromWrapperWhenRequestNameGiven() {
        var created = service.importMcpServers("""
            {"mcpServers":{"old-name":{"command":"uvx","args":["mcp-atlassian"]}}}
            """, "new-name", null, Boolean.FALSE);

        assertEquals(1, created.size());
        assertEquals("new-name", created.getFirst().name);
    }

    @Test
    void keepsDeclaredNamesWhenImportingSeveralServers() {
        var created = service.importMcpServers("""
            {"mcpServers":{"first":{"command":"npx"},"second":{"url":"https://example.com/mcp"}}}
            """, "requested", null, Boolean.FALSE);

        assertEquals(2, created.size());
        assertTrue(created.stream().anyMatch(entity -> "first".equals(entity.name)));
        assertTrue(created.stream().anyMatch(entity -> "second".equals(entity.name)));
    }

    @Test
    void rejectsMalformedJsonAsBadRequestWithoutWriting() {
        var error = assertThrows(BadRequestException.class,
            () -> service.importMcpServers("{not-json", null, null, Boolean.FALSE));

        assertTrue(error.getMessage().contains("valid JSON"));
        verify(collection, never()).insert(any());
    }

    @Test
    void rejectsJsonNullAsBadRequestWithoutWriting() {
        assertThrows(BadRequestException.class,
            () -> service.importMcpServers("null", null, null, Boolean.FALSE));

        verify(collection, never()).insert(any());
    }

    @Test
    void validatesEveryServerBeforeWritingAnyRecord() {
        var error = assertThrows(BadRequestException.class, () -> service.importMcpServers("""
            {"mcpServers":{"valid":{"command":"npx"},"invalid":{"headers":{"Authorization":"Bearer secret"}}}}
            """, null, null, Boolean.FALSE));

        assertTrue(error.getMessage().contains("invalid"));
        assertFalse(error.getMessage().contains("secret"));
        verify(collection, never()).insert(any());
    }

    @Test
    void rejectsBlankServerName() {
        assertThrows(BadRequestException.class, () -> service.importMcpServers("""
            {"mcpServers":{" ":{"url":"https://example.com/mcp"}}}
            """, null, null, Boolean.FALSE));

        verify(collection, never()).insert(any());
    }

    @Test
    void rejectsSingleServerConfigWithoutAnyName() {
        var error = assertThrows(BadRequestException.class, () -> service.importMcpServers("""
            {"command":"uvx","args":["mcp-atlassian"]}
            """, null, null, Boolean.FALSE));

        assertTrue(error.getMessage().contains("name"));
        verify(collection, never()).insert(any());
    }

    @Test
    void rejectsConfigWithNeitherServersNorTransport() {
        var error = assertThrows(BadRequestException.class,
            () -> service.importMcpServers("{}", null, null, Boolean.FALSE));

        assertTrue(error.getMessage().contains("mcpServers"));
        verify(collection, never()).insert(any());
    }

    @Test
    void rejectsSandboxTransportOnUrlServer() {
        var error = assertThrows(BadRequestException.class, () -> service.importMcpServers("""
            {"mcpServers":{"remote":{"url":"https://example.com/mcp","transport":"sandbox_hosted"}}}
            """, null, null, Boolean.FALSE));

        assertTrue(error.getMessage().contains("sandbox_hosted"));
        verify(collection, never()).insert(any());
    }

    @Test
    void storesNormalizedEntityInCollection() {
        service.importMcpServers("""
            {"mcpServers":{"remote":{"url":"https://example.com/mcp"}}}
            """, null, "ops", Boolean.FALSE);

        var inserted = ArgumentCaptor.forClass(ToolRegistryEntry.class);
        verify(collection).insert(inserted.capture());
        assertEquals("remote", inserted.getValue().name);
        assertEquals("ops", inserted.getValue().category);
        assertFalse(inserted.getValue().enabled);
    }

    @Test
    void retriesFailedServerWhenConnectIsRequested() {
        var entity = new ToolRegistryEntry();
        entity.id = "meta-ads";
        entity.name = "meta-ads-mcp";
        entity.type = ToolType.MCP;
        entity.config = Map.of("url", "https://mcp.facebook.com", "endpoint", "/ads");
        entity.enabled = Boolean.TRUE;
        tools.put(entity.id, entity);

        var manager = mock(McpClientManager.class);
        when(applicationMcpManager.get()).thenReturn(manager);
        when(manager.getState(entity.id))
            .thenReturn(McpClientManager.ConnectionState.FAILED)
            .thenReturn(McpClientManager.ConnectionState.NOT_CONNECTED)
            .thenReturn(McpClientManager.ConnectionState.CONNECTED);

        var state = service.connectMcpServer(entity.id);

        assertEquals(McpClientManager.ConnectionState.CONNECTED, state);
        verify(connectionManager).unregisterMcpServer(entity.id);
        verify(connectionManager).registerMcpServer(entity);
        verify(manager).getClient(entity.id);
    }

}
