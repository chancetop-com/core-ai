package ai.core.server.tool;

import ai.core.server.domain.ToolRegistryEntry;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class McpServerOperationServiceTest {
    @SuppressWarnings("unchecked")
    private static MongoCollection<ToolRegistryEntry> collection() {
        return (MongoCollection<ToolRegistryEntry>) mock(MongoCollection.class);
    }

    private final MongoCollection<ToolRegistryEntry> collection = collection();
    private McpServerOperationService service;

    @BeforeEach
    void setUp() {
        service = new McpServerOperationService(
            new HashMap<>(),
            mock(McpServerConnectionManager.class),
            mock(ApplicationMcpManager.class)
        );
        service.setToolRegistryCollection(collection);
    }

    @Test
    void importsCommandServerAsSandboxHostedWithoutChangingNestedValues() {
        var created = service.importMcpServers("""
            {"mcpServers":{"local-tools":{"command":"npx","args":["-y","@scope/server"],"env":{"API_TOKEN":"secret"}}}}
            """, "development", Boolean.FALSE);

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
            """, null, Boolean.FALSE);

        assertEquals(1, created.size());
        var config = created.getFirst().config;
        assertEquals("https://mcp.facebook.com", config.get("url"));
        assertEquals("/ads", config.get("endpoint"));
        assertEquals("{\"Authorization\":\"Bearer secret\"}", config.get("headers"));
        assertFalse(config.containsKey("transport"));
    }

    @Test
    void rejectsMalformedJsonAsBadRequestWithoutWriting() {
        var error = assertThrows(BadRequestException.class,
            () -> service.importMcpServers("{not-json", null, Boolean.FALSE));

        assertTrue(error.getMessage().contains("valid JSON"));
        verify(collection, never()).insert(any());
    }

    @Test
    void rejectsJsonNullAsBadRequestWithoutWriting() {
        assertThrows(BadRequestException.class,
            () -> service.importMcpServers("null", null, Boolean.FALSE));

        verify(collection, never()).insert(any());
    }

    @Test
    void validatesEveryServerBeforeWritingAnyRecord() {
        var error = assertThrows(BadRequestException.class, () -> service.importMcpServers("""
            {"mcpServers":{"valid":{"command":"npx"},"invalid":{"headers":{"Authorization":"Bearer secret"}}}}
            """, null, Boolean.FALSE));

        assertTrue(error.getMessage().contains("invalid"));
        assertFalse(error.getMessage().contains("secret"));
        verify(collection, never()).insert(any());
    }

    @Test
    void rejectsBlankServerName() {
        assertThrows(BadRequestException.class, () -> service.importMcpServers("""
            {"mcpServers":{" ":{"url":"https://example.com/mcp"}}}
            """, null, Boolean.FALSE));

        verify(collection, never()).insert(any());
    }

    @Test
    void rejectsSandboxTransportOnUrlServer() {
        var error = assertThrows(BadRequestException.class, () -> service.importMcpServers("""
            {"mcpServers":{"remote":{"url":"https://example.com/mcp","transport":"sandbox_hosted"}}}
            """, null, Boolean.FALSE));

        assertTrue(error.getMessage().contains("sandbox_hosted"));
        verify(collection, never()).insert(any());
    }

    @Test
    void storesNormalizedEntityInCollection() {
        service.importMcpServers("""
            {"mcpServers":{"remote":{"url":"https://example.com/mcp"}}}
            """, "ops", Boolean.FALSE);

        var inserted = ArgumentCaptor.forClass(ToolRegistryEntry.class);
        verify(collection).insert(inserted.capture());
        assertEquals("remote", inserted.getValue().name);
        assertEquals("ops", inserted.getValue().category);
        assertFalse(inserted.getValue().enabled);
    }

}
