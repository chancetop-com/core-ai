package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.server.domain.AgentDefinition;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Attaches the memory capability to a session or run assembly.
 *
 * <p>Every memory-enabled agent gets the read tools ({@code search_memory}, {@code read_memory}) — the same
 * gate the run path already used for search. Only a personal assistant fork gets the write tool
 * ({@code extract_memory_now}) plus its prompt section, because its agent id is per user.
 *
 * <p>Both entry paths (create and rebuild) must call into here: a session that outlives a restart or an idle
 * cleanup keeps the same tools and prompt sections only if the rebuild attaches them as well.
 *
 * @author Xander
 */
public final class MemoryCapability {
    public static void attach(ToolRegistry registry, ExecutionContext context, AgentDefinition definition,
                              AgentMemoryService agentMemoryService) {
        if (registry == null || definition == null || agentMemoryService == null) return;
        var config = definition.publishedConfig;
        var enableMemory = config != null ? config.enableMemory : definition.enableMemory;
        if (AgentMemoryService.memoryEnabled(enableMemory)) {
            var readTools = new ArrayList<ToolCall>();
            readTools.add(new SearchMemoryTool(definition.id, agentMemoryService));
            readTools.add(new ReadMemoryTool(definition.id, agentMemoryService));
            registry.registerProvider(ListToolProvider.of("memory-read", readTools));
        }
        if (AgentMemoryService.rememberEnabled(definition)) {
            registry.registerProvider(ListToolProvider.of("memory-write",
                    List.of(new ExtractMemoryNowTool(definition.id, agentMemoryService))));
            MemoryWritePrompt.attach(context, definition);
        }
    }

    private MemoryCapability() {
    }
}
