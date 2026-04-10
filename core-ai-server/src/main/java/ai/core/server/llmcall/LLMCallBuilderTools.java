package ai.core.server.llmcall;

import ai.core.llm.LLMProviders;
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
public class LLMCallBuilderTools {
    @Inject
    LLMProviders llmProviders;

    @Inject
    AgentDefinitionService agentDefinitionService;

    @Inject
    ToolRegistryService toolRegistryService;

    public void initialize() {
        var testTool = TestLLMCallTool.create(llmProviders);
        var publishTool = PublishLLMCallTool.create(agentDefinitionService);
        toolRegistryService.registerToolSet("builtin-llm-call-builder", List.of(testTool, publishTool));
    }

    public List<ToolCall> tools() {
        var toolRef = new ToolRef("builtin-llm-call-builder", ToolSourceType.BUILTIN);
        return toolRegistryService.resolveToolRefs(List.of(toolRef));
    }
}
