package ai.core.server.mcphub;

import ai.core.mcp.client.McpClientManager;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.tool.ToolRegistryService;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolCatalogServiceTest {
    private ToolRegistryService registry;
    private McpToolCatalogService catalog;

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistryService.class);
        catalog = new McpToolCatalogService();
        catalog.toolRegistryService = registry;
    }

    @Test
    void refreshBuildsSnapshotsAndSearchFindsExactNameMatches() {
        var jira = entry("jira", "srv-jira");
        var github = entry("github", "srv-github");
        stubEntries(jira, github);
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("create_issue", "Create a Jira issue")));
        when(registry.listMcpServerToolDetails("srv-github")).thenReturn(List.of(tool("open_issue", "Open a GitHub issue")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        when(registry.getMcpServerState("srv-github")).thenReturn(McpClientManager.ConnectionState.CONNECTED);

        catalog.refresh();
        var results = catalog.search("create_issue", null, null);

        assertEquals(1, results.size(), "github tool does not match any token");
        assertEquals("jira", results.getFirst().tool().serverName());
        assertTrue(results.getFirst().score() >= 100, "exact name match gets the +100 bonus");
        assertFalse(results.getFirst().tool().stale());
        assertEquals("jira/create_issue", results.getFirst().tool().qualifiedName());
        assertEquals("mcp-tool:srv-jira:create_issue", results.getFirst().tool().refId());
    }

    @Test
    void exactNameRankingBeatsPartialTokenHits() {
        stubEntries(entry("jira", "srv-jira"));
        when(registry.listMcpServerToolDetails("srv-jira"))
                .thenReturn(List.of(tool("create_issue", "Create a Jira issue"), tool("create", "A shortcut tool")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        var results = catalog.search("create", null, null);

        assertEquals("create", results.getFirst().tool().name(), "exact tool name ranks first");
        assertTrue(results.getFirst().score() > results.get(1).score());
    }

    @Test
    void searchDropsToolsWhenAnyTokenMisses() {
        stubEntries(entry("jira", "srv-jira"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("create_issue", "Create a Jira issue")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        assertEquals(1, catalog.search("create issue", null, null).size());
        assertTrue(catalog.search("create nonsense", null, null).isEmpty());
    }

    @Test
    void searchAppliesLimitAndServerFilter() {
        stubEntries(entry("jira", "srv-jira"), entry("github", "srv-github"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("a", "alpha"), tool("b", "beta")));
        when(registry.listMcpServerToolDetails("srv-github")).thenReturn(List.of(tool("c", "gamma")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        when(registry.getMcpServerState("srv-github")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        assertEquals(2, catalog.search(null, "jira", null).size());
        assertEquals(1, catalog.search(null, null, 1).size());
        assertEquals(3, catalog.search(null, null, 10).size());
    }

    @Test
    void failingServerKeepsPreviousSnapshotMarkedStale() {
        stubEntries(entry("jira", "srv-jira"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("create_issue", "Create a Jira issue")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();
        assertEquals(1, catalog.search(null, null, 10).size());

        // server goes down: empty details + FAILED keeps the previous snapshot but marks it stale
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of());
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.FAILED);
        catalog.refresh();

        var stale = catalog.search(null, null, 10);
        assertEquals(1, stale.size());
        assertTrue(stale.getFirst().tool().stale());
        var servers = catalog.listServers();
        assertTrue(servers.getFirst().stale());
        assertEquals(1, servers.getFirst().toolCount());
    }

    @Test
    void disabledServerSnapshotIsDroppedOnRefresh() {
        stubEntries(entry("jira", "srv-jira"), entry("github", "srv-github"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("a", "alpha")));
        when(registry.listMcpServerToolDetails("srv-github")).thenReturn(List.of(tool("b", "beta")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        when(registry.getMcpServerState("srv-github")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();
        assertEquals(2, catalog.search(null, null, 10).size());

        stubEntries(entry("github", "srv-github"));
        catalog.refresh();
        assertEquals(1, catalog.search(null, null, 10).size());
        assertNull(catalog.findTool("jira", "a"));
    }

    @Test
    void invalidateForcesOnDemandReload() {
        stubEntries(entry("jira", "srv-jira"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("create_issue", "Create a Jira issue")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();
        assertEquals(1, catalog.search(null, null, 10).size());
        verify(registry, times(1)).listMcpServerToolDetails("srv-jira");

        catalog.invalidate("srv-jira");
        assertNotNull(catalog.findTool("jira", "create_issue"));
        verify(registry, times(2)).listMcpServerToolDetails("srv-jira");
    }

    @Test
    void disabledEntryIsNotVisible() {
        var disabled = entry("jira", "srv-jira");
        disabled.enabled = Boolean.FALSE;
        stubEntries(disabled);
        catalog.refresh();
        assertNull(catalog.entryByName("jira"));
        assertTrue(catalog.search(null, null, 10).isEmpty());
        assertTrue(catalog.listServers().isEmpty());
    }

    private void stubEntries(ToolRegistryEntry... entries) {
        when(registry.listTools(null)).thenReturn(List.of(entries));
    }

    private ToolRegistryEntry entry(String name, String id) {
        var entry = new ToolRegistryEntry();
        entry.id = id;
        entry.name = name;
        entry.type = ToolType.MCP;
        entry.category = "test";
        entry.config = java.util.Map.of();
        entry.enabled = Boolean.TRUE;
        entry.createdAt = ZonedDateTime.now();
        return entry;
    }

    private McpSchema.Tool tool(String name, String description) {
        return McpSchema.Tool.builder().name(name).description(description).build();
    }
}
