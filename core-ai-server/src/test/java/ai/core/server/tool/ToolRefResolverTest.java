package ai.core.server.tool;

import ai.core.api.server.run.LLMCallRequest;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolType;
import ai.core.server.llmcall.LLMCallTool;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolRefResolverTest {
    @Test
    void declaredTypeMismatchWithRegistryFailsClosed() {
        var registry = new ToolRegistryEntry();
        registry.id = "custom-builtin";
        registry.type = ToolType.BUILTIN;
        registry.config = Map.of("set", "builtin-planning");

        var ref = new ToolRef();
        ref.id = "custom-builtin";
        ref.type = ToolSourceType.MCP;

        var resolver = new ToolRefResolver(Map.of(registry.id, registry), null, Map.of());

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(List.of(ref)));
    }

    @Test
    void resolvesLlmCallRefIntoCallableToolForAuthenticatedCaller() {
        var definition = new AgentDefinition();
        definition.id = "def1";
        definition.name = "image_recognition";
        definition.description = "Recognize menu from image";
        definition.type = DefinitionType.LLM_CALL;

        var service = mock(AgentDefinitionService.class);
        when(service.resolveLlmCallToolDefinition("def1", "caller-1")).thenReturn(definition);
        var executor = new LLMCallExecutor() {
            @Override
            public Result execute(AgentDefinition d, String input, List<LLMCallRequest.Attachment> attachments) {
                return new Result("{\"ok\":true}", 1, 1);
            }
        };
        var resolver = new ToolRefResolver(Map.of(), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);
        resolver.setLlmCallExecutor(executor);

        var resolved = resolver.resolve(List.of(ToolRef.fromLegacyToolId("llm-call:def1")), null, "caller-1");

        assertEquals(1, resolved.size());
        assertTrue(resolved.getFirst() instanceof LLMCallTool);
        verify(service).resolveLlmCallToolDefinition("def1", "caller-1");
    }

    @Test
    void llmCallRefWithoutCallerFailsClosed() {
        var service = mock(AgentDefinitionService.class);
        var resolver = new ToolRefResolver(Map.of(), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(List.of(ToolRef.fromLegacyToolId("llm-call:missing"))));
        verifyNoInteractions(service);
    }

    @Test
    void malformedLlmCallRefFailsClosedWithoutDefinitionLookup() {
        var service = mock(AgentDefinitionService.class);
        var resolver = new ToolRefResolver(Map.of(), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);

        var malformed = ToolRef.of("forged-id", ToolSourceType.LLM_CALL);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(List.of(malformed), null, "caller-1"));
        verifyNoInteractions(service);
    }

    @Test
    void rawUntypedLlmCallRefWithoutCallerFailsClosed() {
        var service = mock(AgentDefinitionService.class);
        var resolver = new ToolRefResolver(Map.of(), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);
        var raw = new ToolRef();
        raw.id = "llm-call:target";

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(List.of(raw)));
        verifyNoInteractions(service);
    }

    @Test
    void reservedLlmCallPrefixOverridesContradictoryDeclaredType() {
        var service = mock(AgentDefinitionService.class);
        var resolver = new ToolRefResolver(Map.of(), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);
        var contradictory = ToolRef.of("llm-call:target", ToolSourceType.BUILTIN);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(List.of(contradictory)));
        verifyNoInteractions(service);
    }

    @Test
    void typedLlmCallRefWithoutIdFailsClosed() {
        var service = mock(AgentDefinitionService.class);
        var resolver = new ToolRefResolver(Map.of(), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);
        var malformed = new ToolRef();
        malformed.type = ToolSourceType.LLM_CALL;

        assertThrows(IllegalArgumentException.class,
            () -> resolver.resolve(List.of(malformed), null, "caller-1"));
        verifyNoInteractions(service);
    }

    @Test
    void registryEntryCannotOverrideExplicitLlmCallClassification() {
        var collision = new ToolRegistryEntry();
        collision.id = "llm-call:target";
        collision.type = ToolType.BUILTIN;
        collision.config = Map.of("set", "builtin-planning");
        var service = mock(AgentDefinitionService.class);
        var resolver = new ToolRefResolver(Map.of(collision.id, collision), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);
        var ref = ToolRef.of(collision.id, ToolSourceType.LLM_CALL);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(List.of(ref)));
        verifyNoInteractions(service);
    }

    @Test
    void resolvesIndividualToolInsideDynamicBuiltinGroup() {
        var resolver = new ToolRefResolver(Map.of(), null,
                Map.of("builtin:self-harness", List.of(tool("list_agents"), tool("get_trace"))), null, null, null);

        var resolved = resolver.resolve(List.of(ToolRef.of("builtin:self-harness:get_trace", ToolSourceType.BUILTIN)));

        assertEquals(1, resolved.size());
        assertEquals("get_trace", resolved.getFirst().getName());
    }

    @Test
    void unknownIndividualBuiltinGroupToolResolvesNothing() {
        var resolver = new ToolRefResolver(Map.of(), null,
                Map.of("builtin:self-harness", List.of(tool("list_agents"))), null, null, null);

        assertTrue(resolver.resolve(List.of(ToolRef.of("builtin:self-harness:missing", ToolSourceType.BUILTIN))).isEmpty());
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
