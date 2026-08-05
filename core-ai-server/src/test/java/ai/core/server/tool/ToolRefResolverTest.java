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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRefResolverTest {
    @Test
    void registryTypeOverridesIncorrectRequestType() {
        var registry = new ToolRegistryEntry();
        registry.id = "custom-builtin";
        registry.type = ToolType.BUILTIN;
        registry.config = Map.of("set", "builtin-planning");

        var ref = new ToolRef();
        ref.id = "custom-builtin";
        ref.type = ToolSourceType.MCP;

        var resolver = new ToolRefResolver(Map.of(registry.id, registry), null, Map.of());

        var resolved = resolver.resolve(List.of(ref));

        assertEquals(ai.core.tool.BuiltinTools.GROUPED_SETS.get("builtin-planning").size(), resolved.size());
    }

    @Test
    void resolvesLlmCallRefIntoCallableTool() {
        var definition = new AgentDefinition();
        definition.id = "def1";
        definition.name = "image_recognition";
        definition.description = "Recognize menu from image";
        definition.type = DefinitionType.LLM_CALL;

        var service = new AgentDefinitionService() {
            @Override
            public AgentDefinition getEntity(String id) {
                return definition;
            }
        };
        var executor = new LLMCallExecutor() {
            @Override
            public Result execute(AgentDefinition d, String input, List<LLMCallRequest.Attachment> attachments) {
                return new Result("{\"ok\":true}", 1, 1);
            }
        };
        var resolver = new ToolRefResolver(Map.of(), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);
        resolver.setLlmCallExecutor(executor);

        var resolved = resolver.resolve(List.of(ToolRef.fromLegacyToolId("llm-call:def1")));

        assertEquals(1, resolved.size());
        assertTrue(resolved.getFirst() instanceof LLMCallTool);
    }

    @Test
    void missingLlmCallDefinitionFailsFast() {
        var service = new AgentDefinitionService() {
            @Override
            public AgentDefinition getEntity(String id) {
                throw new RuntimeException("agent not found, id=" + id);
            }
        };
        var resolver = new ToolRefResolver(Map.of(), null, Map.of(), null, null, null);
        resolver.setAgentDefinitionService(service);

        assertThrows(RuntimeException.class,
                () -> resolver.resolve(List.of(ToolRef.fromLegacyToolId("llm-call:missing"))));
    }
}
