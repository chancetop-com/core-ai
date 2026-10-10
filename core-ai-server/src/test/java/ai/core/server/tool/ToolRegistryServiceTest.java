package ai.core.server.tool;

import ai.core.api.server.tool.UpdateMcpServerRequest;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.gateway.GatewayRoutingEngine;
import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class ToolRegistryServiceTest {
    private static ToolRegistryEntry entry(String id, boolean enabled) {
        var entry = new ToolRegistryEntry();
        entry.id = id;
        entry.name = id;
        entry.category = "marketing";
        entry.type = ToolType.MCP;
        entry.enabled = enabled;
        entry.config = Map.of("url", "https://example.com/mcp");
        return entry;
    }

    private static boolean matches(Bson filter, ToolRegistryEntry entry) {
        var query = filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        if (query.containsKey("$or")) {
            return query.getArray("$or").stream().anyMatch(condition -> matches(condition.asDocument(), entry));
        }
        if (query.containsKey("enabled") && query.getBoolean("enabled").getValue() != entry.enabled) return false;
        return !query.containsKey("type") || query.getString("type").getValue().equals(entry.type.name());
    }

    @Mock
    private MongoCollection<ToolRegistryEntry> collection;
    @Mock
    private GatewayRoutingEngine gatewayRoutingEngine;
    @InjectMocks
    private ToolRegistryService service;
    private final Map<String, ToolRegistryEntry> database = new HashMap<>();
    private AutoCloseable mocks;
    private MockedConstruction<McpServerConnectionManager> connections;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        connections = mockConstruction(McpServerConnectionManager.class);
        when(collection.find(any(Bson.class))).thenAnswer(invocation -> {
            Bson filter = invocation.getArgument(0);
            return database.values().stream().filter(entry -> matches(filter, entry)).toList();
        });
        doAnswer(invocation -> {
            ToolRegistryEntry entry = invocation.getArgument(0);
            database.put(entry.id, entry);
            return null;
        }).when(collection).replace(any(ToolRegistryEntry.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        connections.close();
        mocks.close();
    }

    @Test
    void startupKeepsDisabledMcpVisibleWithoutRegisteringItOrOtherDisabledTools() {
        var disabled = entry("google-ads", false);
        database.put(disabled.id, disabled);
        var disabledApi = entry("disabled-api", false);
        disabledApi.type = ToolType.API;
        database.put(disabledApi.id, disabledApi);

        service.initialize(null);

        assertSame(disabled, service.getTool(disabled.id));
        assertTrue(service.listTools("marketing").contains(disabled));
        assertThrows(RuntimeException.class, () -> service.getTool(disabledApi.id));
        verifyNoInteractions(connection());
    }

    @Test
    void syncLoadsDisabledRemoteAndSandboxServersWithoutRegisteringOrWarmingThem() {
        service.initialize(null);
        var remote = entry("google-ads", false);
        var sandbox = entry("sandbox", false);
        sandbox.config = Map.of("transport", "sandbox_hosted", "command", "uvx");
        database.put(remote.id, remote);
        database.put(sandbox.id, sandbox);

        service.syncDatabaseTools();
        service.syncDatabaseTools();

        assertSame(remote, service.getTool(remote.id));
        assertSame(sandbox, service.getTool(sandbox.id));
        verifyNoInteractions(connection());
    }

    @Test
    void disabledServerRemainsEditableAndCanBeEnabledAfterRepeatedSync() {
        var entity = entry("google-ads", true);
        database.put(entity.id, entity);
        service.initialize(null);
        clearInvocations(connection());

        service.disableMcpServer(entity.id);
        service.syncDatabaseTools();
        service.syncDatabaseTools();
        var update = new UpdateMcpServerRequest();
        update.description = "Updated while disabled";
        var edited = service.updateMcpServer(entity.id, update);

        assertFalse(edited.enabled);
        assertEquals(update.description, edited.description);
        assertTrue(service.enableMcpServer(entity.id).enabled);
        verify(connection()).unregisterMcpServer(entity.id);
        verify(connection()).registerMcpServer(entity);
        verify(connection()).warmupMcpServer(entity.id);
    }

    @Test
    void syncRemovesDeletedDatabaseServersAndPreservesConfiguredAndBuiltinTools() {
        var entity = entry("google-ads", true);
        database.put(entity.id, entity);
        service.initialize("{\"configured\":{\"url\":\"https://example.com/mcp\"}}");
        var builtins = service.listTools("builtin");
        assertFalse(builtins.isEmpty());
        clearInvocations(connection());
        database.remove(entity.id);

        service.syncDatabaseTools();

        assertThrows(RuntimeException.class, () -> service.getTool(entity.id));
        assertEquals("configured", service.getTool("config:configured").name);
        assertTrue(service.listTools("builtin").containsAll(builtins));
        verify(connection()).unregisterMcpServer(entity.id);
    }

    @Test
    void syncAppliesDisableFromAnotherServerWithoutRemovingTheRegistryEntry() {
        var enabled = entry("google-ads", true);
        database.put(enabled.id, enabled);
        service.initialize(null);
        clearInvocations(connection());
        var disabled = entry(enabled.id, false);
        database.put(disabled.id, disabled);

        service.syncDatabaseTools();
        service.syncDatabaseTools();

        assertSame(disabled, service.getTool(disabled.id));
        verify(connection()).applyMcpServerState(disabled, false);
    }

    private McpServerConnectionManager connection() {
        return connections.constructed().getFirst();
    }

}
