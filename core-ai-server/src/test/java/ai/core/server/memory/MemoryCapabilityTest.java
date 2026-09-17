package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.prompt.PromptInject;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MemoryCapabilityTest {
    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final ToolRegistry registry = new ToolRegistry();
    private final ExecutionContext context = context();

    @Test
    void memoryDisabledRegistersNothing() {
        MemoryCapability.attach(registry, context, definition(Boolean.FALSE, true), agentMemoryService);

        assertEquals(List.of(), toolNames());
        assertEquals(0, context.getPromptSections().size());
    }

    @Test
    void sharedAgentGetsTheReadToolsOnly() {
        MemoryCapability.attach(registry, context, definition(Boolean.TRUE, false), agentMemoryService);

        assertEquals(List.of("read_memory", "search_memory"), toolNames());
        assertEquals(0, context.getPromptSections().size(), "a shared agent must not be told to remember");
    }

    @Test
    void forkedAssistantAlsoGetsTheWriteToolAndInstruction() {
        MemoryCapability.attach(registry, context, definition(Boolean.TRUE, true), agentMemoryService);

        assertEquals(List.of("extract_memory_now", "read_memory", "search_memory"), toolNames());
        assertEquals(1, context.getPromptSections().size());
        assertEquals(PromptInject.SectionType.MEMORY, context.getPromptSections().getFirst().type());
    }

    @Test
    void publishedConfigDecidesEnableMemory() {
        var definition = definition(null, true);
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.enableMemory = Boolean.FALSE;

        MemoryCapability.attach(registry, context, definition, agentMemoryService);

        assertEquals(List.of(), toolNames());
    }

    @Test
    void missingDefinitionOrRegistryIsIgnored() {
        MemoryCapability.attach(null, context, definition(Boolean.TRUE, true), agentMemoryService);
        MemoryCapability.attach(registry, context, null, agentMemoryService);

        assertEquals(List.of(), toolNames());
        assertEquals(0, context.getPromptSections().size());
    }

    private List<String> toolNames() {
        return registry.getToolCalls().stream().map(ToolCall::getName).sorted().toList();
    }

    private ExecutionContext context() {
        return ExecutionContext.builder()
                .sessionId("session-1")
                .userId("user-1")
                .promptSections(new ArrayList<>())
                .build();
    }

    private AgentDefinition definition(Boolean enableMemory, boolean forked) {
        var definition = new AgentDefinition();
        definition.id = forked ? "assistant:user-1" : "agent-1";
        definition.enableMemory = enableMemory;
        if (forked) definition.forkedFrom = "default-assistant";
        return definition;
    }
}
