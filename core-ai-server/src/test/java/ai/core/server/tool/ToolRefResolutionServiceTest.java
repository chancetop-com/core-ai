package ai.core.server.tool;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolRefResolutionServiceTest {
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
