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
        var outcome = catalog.search("create_issue", null, null);

        assertEquals(1, outcome.tools().size(), "github tool does not match any token");
        assertEquals(1, outcome.servers().size());
        assertEquals("jira", outcome.servers().getFirst().name());
        assertEquals(1, outcome.servers().getFirst().matchedCount());
        assertEquals("jira", outcome.tools().getFirst().tool().serverName());
        assertTrue(outcome.tools().getFirst().score() >= 100, "exact name match gets the +100 bonus");
        assertFalse(outcome.tools().getFirst().tool().stale());
        assertEquals("jira/create_issue", outcome.tools().getFirst().tool().qualifiedName());
        assertEquals("mcp-tool:srv-jira:create_issue", outcome.tools().getFirst().tool().refId());
    }

    @Test
    void exactNameRankingBeatsPartialTokenHits() {
        stubEntries(entry("jira", "srv-jira"));
        when(registry.listMcpServerToolDetails("srv-jira"))
                .thenReturn(List.of(tool("create_issue", "Create a Jira issue"), tool("create", "A shortcut tool")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        var tools = catalog.search("create", null, null).tools();

        assertEquals(2, tools.size());
        assertEquals("create", tools.getFirst().tool().name(), "exact tool name ranks first");
        assertTrue(tools.getFirst().score() > tools.get(1).score());
    }

    @Test
    void searchDropsToolsWhenAnyTokenMisses() {
        stubEntries(entry("jira", "srv-jira"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("create_issue", "Create a Jira issue")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        assertEquals(1, catalog.search("create issue", null, null).tools().size());
        assertTrue(catalog.search("create nonsense", null, null).tools().isEmpty());
        assertTrue(catalog.search("create nonsense", null, null).servers().isEmpty());
    }

    @Test
    void brandServerMatchesRankAboveToolNameHits() {
        var gbp = entry("google-gbp", "srv-gbp");
        var dataforseo = entry("dataforseo", "srv-df");
        stubEntries(gbp, dataforseo);
        when(registry.listMcpServerToolDetails("srv-gbp")).thenReturn(List.of(
                tool("get_reviews", "List reviews of a Google Business Profile location"),
                tool("list_locations", "List locations of a Google Business Profile account")));
        when(registry.listMcpServerToolDetails("srv-df")).thenReturn(List.of(
                tool("google_keyword_data", "Google keyword search volume data"),
                tool("kw_trends", "Keyword trends over time")));
        when(registry.getMcpServerState("srv-gbp")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        when(registry.getMcpServerState("srv-df")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        var outcome = catalog.search("google", null, null);

        assertEquals(2, outcome.servers().size());
        var gbpHit = outcome.servers().getFirst();
        assertEquals("google-gbp", gbpHit.name(), "brand-matched server ranks first");
        assertEquals(2, gbpHit.matchedCount());
        assertTrue(gbpHit.serverScore() > 0);
        assertTrue(outcome.servers().get(1).serverScore() == 0, "tool-name-only server is not brand-matched");
        assertEquals(1, outcome.servers().get(1).matchedCount());

        assertEquals(3, outcome.tools().size(), "round-robin picks 2 from brand server and 1 from the other");
        assertEquals("google-gbp/get_reviews", outcome.tools().get(0).tool().qualifiedName());
        assertEquals("dataforseo/google_keyword_data", outcome.tools().get(1).tool().qualifiedName());
        assertEquals("google-gbp/list_locations", outcome.tools().get(2).tool().qualifiedName());
    }

    @Test
    void searchCapsToolsPerServerAndServerFilterDrillsDown() {
        stubEntries(entry("google-big", "srv-big"));
        when(registry.listMcpServerToolDetails("srv-big")).thenReturn(List.of(
                tool("get_a", "Google alpha tool"),
                tool("get_b", "Google beta tool"),
                tool("get_c", "Google gamma tool"),
                tool("get_d", "Google delta tool"),
                tool("get_e", "Google epsilon tool")));
        when(registry.getMcpServerState("srv-big")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        var outcome = catalog.search("google", null, 200);

        assertEquals(3, outcome.tools().size(), "at most 3 tools per server in query mode");
        assertEquals(5, outcome.servers().getFirst().matchedCount(), "matched count reports the full set");
        var drilled = catalog.search("google", "google-big", null).tools();
        assertEquals(5, drilled.size(), "--on-server drill-down ignores the per-server cap");
    }

    @Test
    void exactServerNameOutranksContainingServerName() {
        stubEntries(entry("gbp", "srv-gbp"), entry("google-gbp", "srv-google-gbp"));
        when(registry.listMcpServerToolDetails("srv-gbp")).thenReturn(List.of(tool("get_x", "X helper")));
        when(registry.listMcpServerToolDetails("srv-google-gbp")).thenReturn(List.of(tool("get_y", "Y helper")));
        when(registry.getMcpServerState("srv-gbp")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        when(registry.getMcpServerState("srv-google-gbp")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        var outcome = catalog.search("gbp", null, null);

        assertEquals("gbp", outcome.servers().get(0).name(), "exact registered-name match ranks above substring");
        assertEquals(2, outcome.tools().size());
    }

    @Test
    void searchAppliesLimitAndServerFilter() {
        stubEntries(entry("jira", "srv-jira"), entry("github", "srv-github"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("a", "alpha"), tool("b", "beta")));
        when(registry.listMcpServerToolDetails("srv-github")).thenReturn(List.of(tool("c", "gamma")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        when(registry.getMcpServerState("srv-github")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();

        assertEquals(2, catalog.search(null, "jira", null).tools().size());
        assertEquals(1, catalog.search(null, null, 1).tools().size());
        assertEquals(3, catalog.search(null, null, 10).tools().size());
        assertTrue(catalog.search(null, null, 10).servers().isEmpty(), "listing mode has no server level");
    }

    @Test
    void failingServerKeepsPreviousSnapshotMarkedStale() {
        stubEntries(entry("jira", "srv-jira"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("create_issue", "Create a Jira issue")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();
        assertEquals(1, catalog.search(null, null, 10).tools().size());

        // server goes down: empty details + FAILED keeps the previous snapshot but marks it stale
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of());
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.FAILED);
        catalog.refresh();

        var stale = catalog.search(null, null, 10).tools();
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
        assertEquals(2, catalog.search(null, null, 10).tools().size());

        stubEntries(entry("github", "srv-github"));
        catalog.refresh();
        assertEquals(1, catalog.search(null, null, 10).tools().size());
        assertNull(catalog.findTool("jira", "a"));
    }

    @Test
    void invalidateForcesOnDemandReload() {
        stubEntries(entry("jira", "srv-jira"));
        when(registry.listMcpServerToolDetails("srv-jira")).thenReturn(List.of(tool("create_issue", "Create a Jira issue")));
        when(registry.getMcpServerState("srv-jira")).thenReturn(McpClientManager.ConnectionState.CONNECTED);
        catalog.refresh();
        assertEquals(1, catalog.search(null, null, 10).tools().size());
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
        assertTrue(catalog.search(null, null, 10).tools().isEmpty());
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
