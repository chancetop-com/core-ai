package ai.core.tool.tools;

import ai.core.agent.internal.AgentHelper;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ToolActivationTool — the progressive tool disclosure mechanism.
 * <p>
 * Two modes based on tool count:
 * <ul>
 *   <li><b>Catalog mode</b> (≤ 30 discoverable tools): full list in description, LLM picks directly</li>
 *   <li><b>Search mode</b> (&gt; 30): LLM searches by keyword first, then activates from results</li>
 * </ul>
 *
 * @author stephen
 */
class ToolActivationToolTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolActivationToolTest.class);

    // ==================== Static Helpers ====================

    private static ToolCall createTool(String name, String description, boolean discoverable) {
        return new ToolCall() {
            {
                setName(name);
                setDescription(description);
                setParameters(List.of());
                setDiscoverable(discoverable);
            }

            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed("executed " + getName());
            }
        };
    }

    private static List<ToolCall> buildLargeToolList(int targetCount) {
        var services = List.of("user_service", "order_service", "payment_service",
                "inventory_service", "notification_service", "auth_service",
                "report_service", "analytics_service", "shipping_service", "billing_service");
        var operations = List.of(
                new String[]{"list", "List all %s records"},
                new String[]{"create", "Create a new %s record"},
                new String[]{"update", "Update an existing %s record"},
                new String[]{"delete", "Delete a %s record"},
                new String[]{"get", "Get a single %s by ID"});

        var tools = new java.util.ArrayList<ToolCall>();
        for (var service : services) {
            for (var op : operations) {
                if (tools.size() >= targetCount) break;
                var domain = service.replace("_service", "");
                tools.add(createTool(
                        service + "_" + op[0],
                        String.format(op[1], domain) + " via " + service + " API. Handles " + domain + " inventory and management.",
                        true));
            }
            if (tools.size() >= targetCount) break;
        }
        return tools;
    }

    // ==================== Instance Fields & Setup ====================

    private List<ToolCall> allToolCalls;
    private ToolCall coreReadFile;

    @BeforeEach
    void setUp() {
        coreReadFile = createTool("read_file", "Read content from a file", false);
        var coreWriteFile = createTool("write_file", "Write content to a file", false);
        var discoverablePostgres = createTool("postgres_query", "Execute SQL queries against PostgreSQL database", true);
        var discoverableMysql = createTool("mysql_connect", "Connect to MySQL database and run queries", true);
        var discoverableRedis = createTool("redis_client", "Redis key-value store operations", true);
        allToolCalls = new java.util.ArrayList<>(List.of(coreReadFile, coreWriteFile, discoverablePostgres, discoverableMysql, discoverableRedis));
    }

    // ==================== Catalog Mode (few tools) ====================

    @Test
    void catalogModeShouldListDiscoverableToolsInDescription() {
        for (var tool : allToolCalls) {
            if (tool.isDiscoverable()) tool.setLlmVisible(false);
        }
        var activationTool = ToolActivationTool.builder().allToolCalls(allToolCalls).build();
        var desc = activationTool.getDescription();
        LOGGER.info("Catalog mode description:\n{}", desc);

        assertTrue(desc.contains("postgres_query"));
        assertTrue(desc.contains("mysql_connect"));
        assertTrue(desc.contains("redis_client"));
        assertFalse(desc.contains("read_file"));
    }

    @Test
    void catalogModeShouldTruncateLongDescriptions() {
        var tools = new java.util.ArrayList<ToolCall>();
        tools.add(createTool("verbose_tool", "A".repeat(200), true));
        var catalog = ToolActivationTool.buildCatalogDescription(tools);

        assertTrue(catalog.contains("..."));
        assertFalse(catalog.contains("A".repeat(200)));
    }

    @Test
    void catalogModeShouldExcludeAlreadyActivatedTools() {
        for (var tool : allToolCalls) {
            if (tool.isDiscoverable()) tool.setLlmVisible(false);
        }
        var activationTool = ToolActivationTool.builder().allToolCalls(allToolCalls).build();

        assertTrue(activationTool.getDescription().contains("postgres_query"));
        activationTool.execute("{\"tool_names\": [\"postgres_query\"]}");

        var descAfter = activationTool.getDescription();
        LOGGER.info("Catalog after activation:\n{}", descAfter);
        assertFalse(descAfter.contains("postgres_query"));
        assertTrue(descAfter.contains("mysql_connect"));
        assertTrue(descAfter.contains("redis_client"));
    }

    // ==================== Search Mode (many tools) ====================

    @Test
    void searchModeShouldBeEnabledWhenToolCountExceedsThreshold() {
        var tools = buildLargeToolList(ToolActivationTool.CATALOG_MODE_THRESHOLD + 10);
        var activationTool = ToolActivationTool.builder().allToolCalls(tools).build();

        var desc = activationTool.getDescription();
        LOGGER.info("Search mode description:\n{}", desc);
        assertTrue(desc.contains("Search"));
        assertFalse(desc.contains("tool_0:"));
    }

    @Test
    void searchShouldMatchByName() {
        var tools = buildLargeToolList(ToolActivationTool.CATALOG_MODE_THRESHOLD + 10);
        for (var tool : tools) {
            if (tool.isDiscoverable()) tool.setLlmVisible(false);
        }
        var activationTool = ToolActivationTool.builder().allToolCalls(tools).build();

        var result = activationTool.execute("{\"query\": \"order\"}");
        LOGGER.info("Search by name result:\n{}", result.getResult());
        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("order_service"));
    }

    @Test
    void searchShouldMatchByDescription() {
        var tools = buildLargeToolList(ToolActivationTool.CATALOG_MODE_THRESHOLD + 10);
        for (var tool : tools) {
            if (tool.isDiscoverable()) tool.setLlmVisible(false);
        }
        var activationTool = ToolActivationTool.builder().allToolCalls(tools).build();

        var result = activationTool.execute("{\"query\": \"inventory\"}");
        LOGGER.info("Search by description result:\n{}", result.getResult());
        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("inventory"));
    }

    @Test
    void searchThenActivateShouldWork() {
        var tools = buildLargeToolList(ToolActivationTool.CATALOG_MODE_THRESHOLD + 10);
        for (var tool : tools) {
            if (tool.isDiscoverable()) tool.setLlmVisible(false);
        }
        var activationTool = ToolActivationTool.builder().allToolCalls(tools).build();
        tools.add(activationTool);

        var searchResult = activationTool.execute("{\"query\": \"order\"}");
        assertTrue(searchResult.isCompleted());

        var activateResult = activationTool.execute("{\"tool_names\": [\"order_service_list\"]}");
        LOGGER.info("Activate after search: {}", activateResult.getResult());
        assertTrue(activateResult.getResult().contains("Activated tools: order_service_list"));

        var visible = AgentHelper.toReqTools(tools);
        assertTrue(visible.stream().anyMatch(t -> t.function.name.equals("order_service_list")));
    }

    @Test
    void searchNoMatchShouldReturnEmptyMessage() {
        var tools = buildLargeToolList(ToolActivationTool.CATALOG_MODE_THRESHOLD + 10);
        var activationTool = ToolActivationTool.builder().allToolCalls(tools).build();

        var result = activationTool.execute("{\"query\": \"xyznonexistent\"}");
        assertTrue(result.getResult().contains("No tools found"));
    }

    // ==================== Activation (shared behavior) ====================

    @Test
    void activateShouldMakeDiscoverableToolVisible() {
        for (var tool : allToolCalls) {
            if (tool.isDiscoverable()) tool.setLlmVisible(false);
        }
        var activationTool = ToolActivationTool.builder().allToolCalls(allToolCalls).build();
        allToolCalls.add(activationTool);

        var visibleBefore = AgentHelper.toReqTools(allToolCalls);
        assertEquals(3, visibleBefore.size());

        activationTool.execute("{\"tool_names\": [\"postgres_query\", \"redis_client\"]}");

        var visibleAfter = AgentHelper.toReqTools(allToolCalls);
        assertEquals(5, visibleAfter.size());
        var names = visibleAfter.stream().map(t -> t.function.name).toList();
        assertTrue(names.contains("postgres_query"));
        assertTrue(names.contains("redis_client"));
        assertFalse(names.contains("mysql_connect"));
    }

    @Test
    void activateIdempotent() {
        for (var tool : allToolCalls) {
            if (tool.isDiscoverable()) tool.setLlmVisible(false);
        }
        var activationTool = ToolActivationTool.builder().allToolCalls(allToolCalls).build();

        activationTool.execute("{\"tool_names\": [\"postgres_query\"]}");
        var result = activationTool.execute("{\"tool_names\": [\"postgres_query\"]}");
        assertTrue(result.isCompleted());
    }

    @Test
    void activateNonexistentShouldReportNotFound() {
        var activationTool = ToolActivationTool.builder().allToolCalls(allToolCalls).build();
        var result = activationTool.execute("{\"tool_names\": [\"nonexistent_tool\"]}");
        assertTrue(result.getResult().contains("not found"));
    }

    @Test
    void coreToolsShouldNeverBeAffected() {
        for (var tool : allToolCalls) {
            if (tool.isDiscoverable()) tool.setLlmVisible(false);
        }
        var activationTool = ToolActivationTool.builder().allToolCalls(allToolCalls).build();

        var result = activationTool.execute("{\"tool_names\": [\"read_file\"]}");
        assertTrue(result.getResult().contains("not found"));
        assertTrue(coreReadFile.isLlmVisible());
    }

    @Test
    void noParamsShouldFail() {
        var activationTool = ToolActivationTool.builder().allToolCalls(allToolCalls).build();
        var result = activationTool.execute("{\"tool_names\": []}");
        assertTrue(result.isFailed());
    }
}
