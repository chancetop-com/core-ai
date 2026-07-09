package ai.core.server.agentbuilder;

import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.skill.SkillService;
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

    @Inject
    SkillService skillService;

    @Inject
    DatasetService datasetService;

    public void initialize() {
        var listTool = ListAgentsTool.create(agentDefinitionService);
        var createTool = CreateAgentDraftTool.create(agentDefinitionService);
        var updateTool = UpdateAgentDraftTool.create(agentDefinitionService);
        var publishTool = PublishAgentDraftTool.create(agentDefinitionService);
        var getDetailTool = GetAgentDetailTool.create(agentDefinitionService);
        var listSkillsTool = ListSkillsTool.create(skillService);
        var listDatasetsTool = ListDatasetsTool.create(datasetService);
        var listToolsTool = ListToolsTool.create(toolRegistryService);
        toolRegistryService.registerToolSet("builtin-agent-builder", List.of(
            listTool, createTool, updateTool, publishTool,
            getDetailTool, listSkillsTool, listDatasetsTool, listToolsTool
        ));
    }

    public List<ToolCall> tools() {
        var agentBuilderRef = ToolRef.of("builtin-agent-builder", ToolSourceType.BUILTIN);
        var allToolsRef = ToolRef.of("builtin-all", ToolSourceType.BUILTIN);
        return toolRegistryService.resolveToolRefs(List.of(agentBuilderRef, allToolsRef));
    }
}
