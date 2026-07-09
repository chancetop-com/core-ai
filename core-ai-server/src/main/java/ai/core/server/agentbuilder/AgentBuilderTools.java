package ai.core.server.agentbuilder;

import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.selfharness.SelfHarnessTools;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import core.framework.inject.Inject;

import java.util.List;

/**
 * Agent builder session tools — now delegates agent management tools to the
 * {@link SelfHarnessTools} builtin group so they can be dynamically configured
 * for any agent, not just the builder.
 */
public class AgentBuilderTools {
    @Inject
    ToolRegistryService toolRegistryService;

    public void initialize() {
        // The self-harness tool group is registered by SelfHarnessTools.initialize().
    }

    public List<ToolCall> tools() {
        var selfHarnessRef = ToolRef.of(SelfHarnessTools.TOOL_SET_NAME, ToolSourceType.BUILTIN);
        var allToolsRef = ToolRef.of("builtin-all", ToolSourceType.BUILTIN);
        return toolRegistryService.resolveToolRefs(List.of(selfHarnessRef, allToolsRef));
    }
}
