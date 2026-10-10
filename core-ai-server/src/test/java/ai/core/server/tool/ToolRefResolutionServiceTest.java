package ai.core.server.tool;

import ai.core.agent.ExecutionContext;
import ai.core.mcp.client.McpClientManager;
import ai.core.sandbox.Sandbox;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.run.LLMCallExecutor;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandboxhub.SandboxHubCatalog;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.tools.MediaModelHint;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolRefResolutionServiceTest {
    @Test
    void multipleSelectedMcpToolsRemainCallableAndVisibleWithoutExposingUnselectedTools() {
        var manager = mock(McpClientManager.class);
        when(manager.hasServer("gbp-id")).thenReturn(Boolean.TRUE);
        when(manager.safeListTools("gbp-id")).thenReturn(List.of(
                mcpTool("list_locations"), mcpTool("get_reviews"), mcpTool("delete_location")));
        when(manager.safeCallTool("gbp-id", "list_locations", "{}"))
                .thenReturn(ToolCallResult.completed("locations"));
        when(manager.safeCallTool("gbp-id", "get_reviews", "{}"))
                .thenReturn(ToolCallResult.completed("reviews"));
        var service = mcpService(manager);

        var registry = service.resolveToToolRegistry(List.of(
                ToolRef.of("mcp-tool:gbp-id:list_locations", ToolSourceType.MCP),
                ToolRef.of("mcp-tool:gbp-id:get_reviews", ToolSourceType.MCP)), null);

        assertEquals(List.of("get_reviews", "list_locations"), toolNames(registry));
        var dispatch = registry.materialize().getDispatchMap();
        assertEquals(Set.of("list_locations", "get_reviews"), dispatch.keySet());
        assertEquals("locations", dispatch.get("list_locations").execute("{}").getResult());
        assertEquals("reviews", dispatch.get("get_reviews").execute("{}").getResult());
        var context = ExecutionContext.empty();
        context.setToolRegistry(registry);
        var catalog = SandboxHubCatalog.of(context);
        assertEquals(List.of("mcp:google-gbp:get_reviews", "mcp:google-gbp:list_locations"),
                catalog.entries().stream().map(SandboxHubCatalog.Entry::refId).toList());
        assertTrue(catalog.entries().stream().allMatch(SandboxHubCatalog.Entry::callable));
    }

    @Test
    void duplicateMcpSelectionsDoNotChangeTheSelectedSetWhenOrderIsReversed() {
        var service = mcpService(mcpManager(List.of("list_locations", "get_reviews", "delete_location")));
        var locations = ToolRef.of("mcp-tool:gbp-id:list_locations", ToolSourceType.MCP);
        var reviews = ToolRef.of("mcp-tool:gbp-id:get_reviews", ToolSourceType.MCP);

        var forward = service.resolveToToolRegistry(List.of(locations, reviews, locations), null);
        var reverse = service.resolveToToolRegistry(List.of(reviews, locations, reviews), null);

        assertEquals(List.of("get_reviews", "list_locations"), toolNames(forward));
        assertEquals(List.of("get_reviews", "list_locations"), toolNames(reverse));

        var nextResolution = service.resolveToToolRegistry(List.of(locations), null);
        assertEquals(List.of("list_locations"), toolNames(nextResolution));
    }

    @Test
    void mcpSelectionsStayWithinTheirOwnServerInProvidersAndCatalog() {
        var manager = mock(McpClientManager.class);
        when(manager.hasServer("gbp-id")).thenReturn(Boolean.TRUE);
        when(manager.hasServer("brightlocal-id")).thenReturn(Boolean.TRUE);
        when(manager.safeListTools("gbp-id")).thenReturn(List.of(
                mcpTool("list_locations"), mcpTool("get_reviews"), mcpTool("delete_location")));
        when(manager.safeListTools("brightlocal-id")).thenReturn(List.of(
                mcpTool("list_reports"), mcpTool("get_rankings"), mcpTool("delete_report")));
        var gbp = new ToolRegistryEntry();
        gbp.id = "gbp-id";
        gbp.name = "google-gbp";
        gbp.type = ToolType.MCP;
        gbp.config = Map.of();
        var brightlocal = new ToolRegistryEntry();
        brightlocal.id = "brightlocal-id";
        brightlocal.name = "brightlocal";
        brightlocal.type = ToolType.MCP;
        brightlocal.config = Map.of();
        var applicationManager = new ApplicationMcpManager();
        applicationManager.set(manager);
        var dependencies = new McpResolutionDependencies(null, null, applicationManager);
        var service = new ToolRefResolutionService(Map.of(gbp.id, gbp, brightlocal.id, brightlocal),
                Map.of(), dependencies, null, null, null);

        var registry = service.resolveToToolRegistry(List.of(
                ToolRef.of("mcp-tool:gbp-id:list_locations", ToolSourceType.MCP),
                ToolRef.of("mcp-tool:brightlocal-id:list_reports", ToolSourceType.MCP),
                ToolRef.of("mcp-tool:gbp-id:get_reviews", ToolSourceType.MCP),
                ToolRef.of("mcp-tool:brightlocal-id:get_rankings", ToolSourceType.MCP)), null);

        assertEquals(Set.of("mcp:google-gbp", "mcp:brightlocal"), registry.providers().keySet());
        assertEquals(Set.of("list_locations", "get_reviews"), registry.getProvider("mcp:google-gbp").provide().keySet());
        assertEquals(Set.of("list_reports", "get_rankings"), registry.getProvider("mcp:brightlocal").provide().keySet());
        var context = ExecutionContext.empty();
        context.setToolRegistry(registry);
        assertEquals(List.of("mcp:brightlocal:get_rankings", "mcp:brightlocal:list_reports",
                        "mcp:google-gbp:get_reviews", "mcp:google-gbp:list_locations"),
                SandboxHubCatalog.of(context).entries().stream().map(SandboxHubCatalog.Entry::refId).toList());
    }

    @Test
    void configuredMcpServerSelectionsMergeAcrossPrefixedAndUnprefixedLookupKeys() {
        var manager = mock(McpClientManager.class);
        when(manager.hasServer("google-gbp")).thenReturn(Boolean.TRUE);
        when(manager.safeListTools("google-gbp")).thenReturn(List.of(
                mcpTool("list_locations"), mcpTool("get_reviews"), mcpTool("delete_location")));
        var entry = new ToolRegistryEntry();
        entry.id = "config:google-gbp";
        entry.name = "google-gbp";
        entry.type = ToolType.MCP;
        entry.config = Map.of();
        var applicationManager = new ApplicationMcpManager();
        applicationManager.set(manager);
        var dependencies = new McpResolutionDependencies(null, null, applicationManager);
        var service = new ToolRefResolutionService(Map.of(entry.id, entry), Map.of(), dependencies, null, null, null);

        var registry = service.resolveToToolRegistry(List.of(
                ToolRef.of("mcp-tool:config:google-gbp:list_locations", ToolSourceType.MCP),
                ToolRef.of("mcp-tool:get_reviews", ToolSourceType.MCP, "google-gbp")), null);

        assertEquals(List.of("get_reviews", "list_locations"), toolNames(registry));
    }

    @Test
    void wholeMcpServerSelectionIsNotNarrowedByIndividualSelectionInEitherOrder() {
        var service = mcpService(mcpManager(List.of("list_locations", "get_reviews", "delete_location")));
        var whole = ToolRef.of("gbp-id", ToolSourceType.MCP);
        var selected = ToolRef.of("mcp-tool:gbp-id:list_locations", ToolSourceType.MCP);

        var wholeFirst = service.resolveToToolRegistry(List.of(whole, selected), null);
        var wholeLast = service.resolveToToolRegistry(List.of(selected, whole), null);

        assertEquals(List.of("delete_location", "get_reviews", "list_locations"), toolNames(wholeFirst));
        assertEquals(List.of("delete_location", "get_reviews", "list_locations"), toolNames(wholeLast));
    }

    @Test
    void legacyWholeMcpServerSelectionIsNotNarrowedInEitherOrder() {
        var service = mcpService(mcpManager(List.of("list_locations", "get_reviews", "delete_location")));
        var whole = ToolRef.fromLegacyToolId("gbp-id");
        var selected = ToolRef.of("mcp-tool:gbp-id:list_locations", ToolSourceType.MCP);

        var wholeFirst = service.resolveToToolRegistry(List.of(whole, selected), null);
        var wholeLast = service.resolveToToolRegistry(List.of(selected, whole), null);

        assertEquals(List.of("delete_location", "get_reviews", "list_locations"), toolNames(wholeFirst));
        assertEquals(List.of("delete_location", "get_reviews", "list_locations"), toolNames(wholeLast));
    }

    @Test
    void wholeApplicationMcpServerStillDiscoversNewToolsAfterMergingSelections() {
        var manager = mcpManager(List.of("list_locations"));
        var service = mcpService(manager);
        var registry = service.resolveToToolRegistry(List.of(
                ToolRef.of("gbp-id", ToolSourceType.MCP),
                ToolRef.of("mcp-tool:gbp-id:get_reviews", ToolSourceType.MCP)), null);
        assertEquals(List.of("list_locations"), toolNames(registry));

        when(manager.safeListTools("gbp-id")).thenReturn(List.of(
                mcpTool("list_locations"), mcpTool("get_reviews"), mcpTool("new_tool")));

        assertEquals(List.of("get_reviews", "list_locations", "new_tool"), toolNames(registry));
    }

    @Test
    void mergedMcpSelectionsExecuteThroughTheirOwnSessionManager() {
        var application = mcpManager(List.of("list_locations", "get_reviews"));
        var firstSession = mcpManager(List.of("list_locations", "get_reviews"));
        var secondSession = mcpManager(List.of("list_locations", "get_reviews"));
        when(application.safeCallTool("gbp-id", "list_locations", "{}"))
                .thenReturn(ToolCallResult.completed("application locations"));
        when(firstSession.safeCallTool("gbp-id", "list_locations", "{}"))
                .thenReturn(ToolCallResult.completed("first session locations"));
        when(secondSession.safeCallTool("gbp-id", "list_locations", "{}"))
                .thenReturn(ToolCallResult.completed("second session locations"));
        var service = sessionMcpService(application, Map.of("first", firstSession, "second", secondSession));
        var refs = List.of(ToolRef.of("mcp-tool:gbp-id:list_locations", ToolSourceType.MCP),
                ToolRef.of("mcp-tool:gbp-id:get_reviews", ToolSourceType.MCP));

        var firstRegistry = service.resolveToToolRegistry(refs, "first");
        var secondRegistry = service.resolveToToolRegistry(refs, "second");
        var applicationRegistry = service.resolveToToolRegistry(refs, null);

        assertEquals(List.of("get_reviews", "list_locations"), toolNames(firstRegistry));
        assertEquals(List.of("get_reviews", "list_locations"), toolNames(secondRegistry));
        assertEquals(List.of("get_reviews", "list_locations"), toolNames(applicationRegistry));
        assertEquals("first session locations", firstRegistry.materialize().getDispatchMap()
                .get("list_locations").execute("{}").getResult());
        assertEquals("second session locations", secondRegistry.materialize().getDispatchMap()
                .get("list_locations").execute("{}").getResult());
        assertEquals("application locations", applicationRegistry.materialize().getDispatchMap()
                .get("list_locations").execute("{}").getResult());
    }

    @Test
    void mergedSessionMcpSelectionRefreshesOnlyAfterItsRegistryIsInvalidated() {
        var firstSession = mcpManager(List.of("list_locations", "get_reviews"));
        var secondSession = mcpManager(List.of("list_locations", "get_reviews"));
        var service = sessionMcpService(mcpManager(List.of()), Map.of("first", firstSession, "second", secondSession));
        var refs = List.of(ToolRef.of("mcp-tool:gbp-id:list_locations", ToolSourceType.MCP),
                ToolRef.of("mcp-tool:gbp-id:get_reviews", ToolSourceType.MCP));
        var firstRegistry = service.resolveToToolRegistry(refs, "first");
        var secondRegistry = service.resolveToToolRegistry(refs, "second");
        assertEquals(List.of("get_reviews", "list_locations"), toolNames(firstRegistry));
        assertEquals(List.of("get_reviews", "list_locations"), toolNames(secondRegistry));

        when(firstSession.safeListTools("gbp-id")).thenReturn(List.of(mcpTool("get_reviews"), mcpTool("unselected_tool")));

        assertEquals(List.of("get_reviews", "list_locations"), toolNames(firstRegistry));
        firstRegistry.invalidateCache("mcp:google-gbp");
        assertEquals(List.of("get_reviews"), toolNames(firstRegistry));
        assertEquals(List.of("get_reviews", "list_locations"), toolNames(secondRegistry));
    }

    @Test
    void callerAwareRegistryResolutionPassesCallerToLlmCallLookup() {
        var definition = new AgentDefinition();
        definition.id = "llm-1";
        definition.name = "llm tool";
        definition.type = DefinitionType.LLM_CALL;
        var definitions = mock(AgentDefinitionService.class);
        when(definitions.resolveLlmCallToolDefinition("llm-1", "caller-1")).thenReturn(definition);
        var service = service(definitions);

        service.resolveToToolRegistry(List.of(ToolRef.fromLegacyToolId("llm-call:llm-1")), null, "caller-1");

        verify(definitions).resolveLlmCallToolDefinition("llm-1", "caller-1");
    }

    @Test
    void authFreeRegistryResolutionRejectsLlmCallRef() {
        var definitions = mock(AgentDefinitionService.class);
        var service = service(definitions);

        assertThrows(IllegalArgumentException.class,
            () -> service.resolveToToolRegistry(List.of(ToolRef.fromLegacyToolId("llm-call:llm-1")), null));
        verifyNoInteractions(definitions);
    }

    @Test
    void authFreeRegistryResolutionRejectsRawUntypedLlmCallRef() {
        var definitions = mock(AgentDefinitionService.class);
        var service = service(definitions);
        var raw = new ToolRef();
        raw.id = "llm-call:llm-1";

        assertThrows(IllegalArgumentException.class,
            () -> service.resolveToToolRegistry(List.of(raw), null));
        verifyNoInteractions(definitions);
    }

    @Test
    void typedLlmCallRefWithoutIdFailsClosed() {
        var definitions = mock(AgentDefinitionService.class);
        var service = service(definitions);
        var malformed = new ToolRef();
        malformed.type = ToolSourceType.LLM_CALL;

        assertThrows(IllegalArgumentException.class,
            () -> service.resolveToToolRegistry(List.of(malformed), null, "caller-1"));
        verifyNoInteractions(definitions);
    }

    @Test
    void registryEntryCannotOverrideExplicitLlmCallClassification() {
        var collision = new ToolRegistryEntry();
        collision.id = "llm-call:llm-1";
        collision.type = ToolType.BUILTIN;
        collision.config = Map.of("set", "builtin-planning");
        var definitions = mock(AgentDefinitionService.class);
        var service = service(definitions, Map.of(collision.id, collision));

        assertThrows(IllegalArgumentException.class, () -> service.resolveToToolRegistry(
            List.of(ToolRef.of(collision.id, ToolSourceType.LLM_CALL)), null));
        verifyNoInteractions(definitions);
    }

    @Test
    void declaredBuiltinCannotResolveRegistryMcpCollision() {
        var collision = new ToolRegistryEntry();
        collision.id = "private-mcp";
        collision.type = ToolType.MCP;
        collision.config = Map.of();
        var definitions = mock(AgentDefinitionService.class);
        var service = service(definitions, Map.of(collision.id, collision));

        assertThrows(IllegalArgumentException.class, () -> service.resolveToToolRegistry(
            List.of(ToolRef.of(collision.id, ToolSourceType.BUILTIN)), null, "caller-1"));
        verifyNoInteractions(definitions);
    }

    @Test
    void resolvesIndividualToolInsideDynamicBuiltinGroupProvider() {
        var definitions = mock(AgentDefinitionService.class);
        var service = service(definitions, Map.of(),
                Map.of("builtin:self-harness", List.of(tool("list_agents"), tool("get_trace"))));

        var registry = service.resolveToToolRegistry(
                List.of(ToolRef.of("builtin:self-harness:list_agents", ToolSourceType.BUILTIN)), null);

        assertEquals(List.of("list_agents"), registry.getToolCalls().stream().map(ToolCall::getName).toList());
    }

    @Test
    void registryResolutionResolvesWholeDynamicallyRegisteredBuiltinGroup() {
        var group = new ToolRegistryEntry();
        group.id = "builtin:short-drama";
        group.type = ToolType.BUILTIN;
        group.config = Map.of();
        var service = service(mock(AgentDefinitionService.class), Map.of(group.id, group),
                Map.of("builtin:short-drama", List.of(tool("drama_list_shots"), tool("drama_note"))));

        var registry = service.resolveToToolRegistry(List.of(ToolRef.of("builtin:short-drama", ToolSourceType.BUILTIN)), null);

        var provider = registry.getProvider("dynamic:builtin:short-drama");
        assertNotNull(provider);
        assertEquals(Set.of("drama_list_shots", "drama_note"),
                provider.provide().values().stream().map(ToolCall::getName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void disablingGatewayImageModelDropsItFromLiveToolRegistryDescription() {
        var entry = new ToolRegistryEntry();
        entry.id = "builtin:builtin-media-generation";
        entry.type = ToolType.BUILTIN;
        entry.config = Map.of("set", ToolProvider.BUILTIN_MEDIA_GENERATION);
        var service = service(mock(AgentDefinitionService.class), Map.of(entry.id, entry));
        var enabledModels = new ArrayList<>(List.of(
                new MediaModelHint("gemini-3.1-flash-image", "gemini-3.1-flash-image", "google"),
                new MediaModelHint("gpt-image-2", "gpt-image-2", "openai")));
        service.setMediaModelHintsProvider(endpoint -> GatewayEndpointType.IMAGE_GENERATION == endpoint
                ? List.copyOf(enabledModels)
                : List.of());

        var registry = service.resolveToToolRegistry(
                List.of(ToolRef.of("builtin-media-generation", ToolSourceType.BUILTIN)), null);
        assertTrue(imageToolDescription(registry).contains("gemini-3.1-flash-image"));

        enabledModels.removeFirst();

        var description = imageToolDescription(registry);
        assertFalse(description.contains("gemini-3.1-flash-image"));
        assertTrue(description.contains("gpt-image-2"));
    }

    private String imageToolDescription(ToolRegistry registry) {
        return registry.materialize().getDispatchMap().get("generate_image").getDescription();
    }

    private List<String> toolNames(ToolRegistry registry) {
        return registry.getToolCalls().stream().map(ToolCall::getName).sorted().toList();
    }

    private McpSchema.Tool mcpTool(String name) {
        return McpSchema.Tool.builder().name(name).description(name).build();
    }

    private McpClientManager mcpManager(List<String> toolNames) {
        var manager = mock(McpClientManager.class);
        when(manager.hasServer("gbp-id")).thenReturn(Boolean.TRUE);
        when(manager.safeListTools("gbp-id")).thenReturn(toolNames.stream().map(this::mcpTool).toList());
        return manager;
    }

    private ToolRefResolutionService mcpService(McpClientManager manager) {
        var applicationManager = new ApplicationMcpManager();
        applicationManager.set(manager);
        return mcpService(new McpResolutionDependencies(null, null, applicationManager), Map.of());
    }

    private ToolRefResolutionService mcpService(McpResolutionDependencies dependencies, Map<String, String> config) {
        var entry = new ToolRegistryEntry();
        entry.id = "gbp-id";
        entry.name = "google-gbp";
        entry.type = ToolType.MCP;
        entry.config = config;
        return new ToolRefResolutionService(Map.of(entry.id, entry), Map.of(), dependencies, null, null, null);
    }

    private ToolRefResolutionService sessionMcpService(McpClientManager application, Map<String, McpClientManager> sessions) {
        var applicationManager = new ApplicationMcpManager();
        applicationManager.set(application);
        var sandboxService = mock(SandboxService.class);
        for (var entry : sessions.entrySet()) {
            when(sandboxService.getSandbox(entry.getKey())).thenReturn(mock(Sandbox.class));
            when(sandboxService.getOrCreateSessionMcpManager(entry.getKey())).thenReturn(entry.getValue());
        }
        var dependencies = new McpResolutionDependencies(mock(McpServerConnectionManager.class), sandboxService, applicationManager);
        return mcpService(dependencies, Map.of("transport", "sandbox_hosted"));
    }

    private ToolRefResolutionService service(AgentDefinitionService definitions) {
        return service(definitions, Map.of(), Map.of());
    }

    private ToolRefResolutionService service(AgentDefinitionService definitions,
                                             Map<String, ToolRegistryEntry> registry) {
        return service(definitions, registry, Map.of());
    }

    private ToolRefResolutionService service(AgentDefinitionService definitions,
                                             Map<String, ToolRegistryEntry> registry,
                                             Map<String, List<ToolCall>> dynamicToolSets) {
        var dependencies = new McpResolutionDependencies(null, null, null);
        var service = new ToolRefResolutionService(registry, dynamicToolSets, dependencies, null, null, null);
        service.setAgentDefinitionService(definitions);
        service.setLlmCallExecutor(mock(LLMCallExecutor.class));
        return service;
    }

    private ToolCall tool(String name) {
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return null;
            }
        };
        tool.setName(name);
        tool.setParameters(List.of());
        return tool;
    }
}
