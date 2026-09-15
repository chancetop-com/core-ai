package ai.core.server.sandboxhub;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.sandboxhub.SandboxHubGroup;
import ai.core.api.server.sandboxhub.SandboxHubToolDetail;
import ai.core.sandbox.SandboxConstants;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ToolExposure;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalog derivation is the contract a script sees: which of the session agent's tools are callable
 * and under which name. Derived from a real {@link ToolRegistry}, so a change in provider ids or tool
 * naming breaks these tests the same way it would break a script.
 *
 * @author xander
 */
class SandboxHubCatalogTest {
    private static SandboxHubGroup group(List<SandboxHubGroup> groups, String kind, String name) {
        for (var group : groups) {
            if (group.kind.equals(kind) && Optional.ofNullable(name).equals(Optional.ofNullable(group.group))) return group;
        }
        throw new AssertionError("group not found: " + kind + "/" + name + ", available: " + groups.size());
    }

    private static SandboxHubToolDetail detail(SandboxHubCatalog.Entry entry) {
        return SandboxHubCatalog.detail(entry, SandboxHubCatalog.defaultTimeoutSeconds(entry));
    }

    private static SandboxHubCatalog.Entry only(SandboxHubCatalog catalog) {
        assertEquals(1, catalog.size(), "expected exactly one catalog entry");
        return catalog.entries().getFirst();
    }

    private static SandboxHubCatalog.Entry entry(List<SandboxHubCatalog.Entry> entries, String name) {
        for (var entry : entries) {
            if (entry.name().equals(name)) return entry;
        }
        throw new AssertionError("entry not found: " + name + ", available: " + entries.size());
    }

    private static SandboxHubCatalog catalog(ToolProvider... providers) {
        var registry = new ToolRegistry();
        for (var provider : providers) {
            registry.registerProvider(provider);
        }
        var context = ExecutionContext.builder().build();
        context.setToolRegistry(registry);
        return SandboxHubCatalog.of(context);
    }

    private static ToolProvider provider(String id, ToolCall... tools) {
        var map = new LinkedHashMap<String, ToolCall>();
        for (var tool : tools) {
            map.put(tool.getName(), tool);
        }
        return new ToolProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Map<String, ToolCall> provide() {
                return map;
            }
        };
    }

    private static ToolCall subAgentTool(String name) {
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed(arguments);
            }

            @Override
            public boolean isSubAgent() {
                return true;
            }
        };
        tool.setName(name);
        tool.setParameters(new ArrayList<>());
        tool.setExposure(ToolExposure.DIRECT);
        return tool;
    }

    private static ToolCall tool(String name) {
        return tool(name, ToolExposure.DIRECT);
    }

    private static ToolCall tool(String name, ToolExposure exposure) {
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed(arguments);
            }
        };
        tool.setName(name);
        tool.setDescription(name);
        tool.setParameters(new ArrayList<>());
        tool.setExposure(exposure);
        return tool;
    }

    @Test
    void mcpToolKeepsServerScopeInRefAndScriptName() {
        var catalog = catalog(provider("mcp:menu-hub", tool("search")));

        var entry = only(catalog);

        assertEquals("mcp", entry.kind());
        assertEquals("menu-hub", entry.group());
        assertEquals("menu-hub/search", entry.path());
        assertEquals("mcp:menu-hub:search", entry.refId());
        assertEquals("menu_hub_search", entry.name());
        assertTrue(entry.callable());
        assertEquals("direct", detail(entry).exposure);
    }

    @Test
    void serverSlugPrefixIsStrippedFromMcpToolName() {
        var catalog = catalog(provider("mcp:menu-hub", tool("menu_hub_search")));

        var entry = only(catalog);

        assertEquals("mcp:menu-hub:search", entry.refId());
        assertEquals("menu_hub_search", entry.name());
    }

    @Test
    void apiOperationRefBecomesServicePath() {
        var catalog = catalog(provider("api-tools:api-operation:menu-api:menu:search", tool("menu_api_menu_search")));

        var entry = only(catalog);

        assertEquals("api", entry.kind());
        assertEquals("menu-api", entry.group());
        assertEquals("menu-api/menu/search", entry.refId());
        assertEquals("menu_api_menu_search", entry.name());
    }

    @Test
    void apiAppRefIsGroupedByApp() {
        var catalog = catalog(provider("api-tools:api-app:menu-api", tool("menu_api")));

        var entry = only(catalog);

        assertEquals("api", entry.kind());
        assertEquals("menu-api", entry.group());
        assertEquals("menu-api", entry.refId());
    }

    @Test
    void llmCallDefinitionIsNamedAfterItsTool() {
        var catalog = catalog(provider("llm-call:def1", tool("image_recognition")));

        var entry = only(catalog);

        assertEquals("llm_call", entry.kind());
        assertEquals("image_recognition", entry.refId());
        assertEquals("image_recognition", entry.name());
        assertNull(entry.group());
    }

    @Test
    void builtinToolWithoutProviderPrefixKeepsItsName() {
        var catalog = catalog(provider(ToolProvider.BUILTIN_WEB, tool("web_search")));

        var entry = only(catalog);

        assertEquals("builtin", entry.kind());
        assertEquals("web_search", entry.refId());
        assertNull(entry.group());
    }

    @Test
    void subAgentGetsAgentKindAndLongDefaultTimeout() {
        var catalog = catalog(provider(ToolProvider.BUILTIN, subAgentTool("menu_agent")));

        var entry = only(catalog);

        assertEquals("agent", entry.kind());
        assertEquals(SandboxHubCatalog.AGENT_TIMEOUT_SECONDS, SandboxHubCatalog.defaultTimeoutSeconds(entry));
    }

    @Test
    void deferredToolsStayCallableAndHiddenOnesDoNot() {
        var catalog = catalog(provider(ToolProvider.BUILTIN, tool("deferred_tool", ToolExposure.DEFERRED),
                tool("hidden_tool", ToolExposure.HIDDEN), tool("activate_tools")));

        assertTrue(entry(catalog.entries(), "deferred_tool").callable());
        assertFalse(entry(catalog.entries(), "hidden_tool").callable());
        assertFalse(entry(catalog.entries(), "activate_tools").callable());
        assertEquals("hidden", detail(entry(catalog.entries(), "hidden_tool")).exposure);
    }

    @Test
    void interceptedToolsNeverReachTheScript() {
        var intercepted = SandboxConstants.INTERCEPTED_TOOLS.iterator().next();
        var catalog = catalog(provider(ToolProvider.BUILTIN, tool(intercepted), tool("web_search")));

        assertNull(catalog.find(intercepted), intercepted);
        assertEquals(1, catalog.size());
    }

    @Test
    void findAcceptsScriptNameAndRawToolName() {
        var catalog = catalog(provider("mcp:menu-hub", tool("search")));

        assertEquals("mcp:menu-hub:search", catalog.find("menu_hub_search").refId());
        assertEquals("mcp:menu-hub:search", catalog.find("search").refId());
        assertNull(catalog.find("missing"));
    }

    @Test
    void groupsCountOneEntryPerKindAndGroup() {
        var catalog = catalog(provider("mcp:menu-hub", tool("search"), tool("get_menu")),
                provider(ToolProvider.BUILTIN_WEB, tool("web_search")));

        var groups = catalog.groups();

        assertEquals(2, groups.size());
        assertEquals(2, group(groups, "mcp", "menu-hub").count);
        assertEquals(1, group(groups, "builtin", null).count);
    }

    @Test
    void contextWithoutRegistryYieldsEmptyCatalog() {
        assertEquals(0, SandboxHubCatalog.of(ExecutionContext.empty()).size());
    }
}
