package ai.core.server;

import ai.core.server.agentbuilder.AgentBuilderTools;
import ai.core.server.llmcall.LLMCallBuilderTools;
import core.framework.module.Module;

/**
 * @author Stephen
 */
public class BuilderToolsModule extends Module {
    @Override
    protected void initialize() {
        var builderTools = bind(LLMCallBuilderTools.class);
        var agentBuilderTools = bind(AgentBuilderTools.class);
        onStartup(builderTools::initialize);
        onStartup(agentBuilderTools::initialize);
    }
}
