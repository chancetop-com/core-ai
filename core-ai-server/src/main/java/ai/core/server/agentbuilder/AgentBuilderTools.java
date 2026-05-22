package ai.core.server.agentbuilder;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.tool.ToolRegistryService;
import ai.core.tool.ToolCall;
import core.framework.inject.Inject;

import java.util.List;

/**
 * @author stephen
 */
public class AgentBuilderTools {
    @Inject
    AgentDefinitionService agentDefinitionService;

    @Inject
    ToolRegistryService toolRegistryService;

    public void initialize() {
        var createTool = CreateAgentDraftTool.create(agentDefinitionService);
        var updateTool = UpdateAgentDraftTool.create(agentDefinitionService);
        var publishTool = PublishAgentDraftTool.create(agentDefinitionService);
        toolRegistryService.registerToolSet("builtin-agent-builder", List.of(createTool, updateTool, publishTool));
    }

    public List<ToolCall> tools() {
        var toolRef = ToolRef.of("builtin-agent-builder", ToolSourceType.BUILTIN);
        return toolRegistryService.resolveToolRefs(List.of(toolRef));
    }
}
