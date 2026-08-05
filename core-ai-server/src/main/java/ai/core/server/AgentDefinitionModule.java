package ai.core.server;

import ai.core.api.server.AgentDefinitionWebService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.agent.GenerateService;
import ai.core.server.agent.JavaToSchemaService;
import ai.core.server.run.LLMCallExecutor;
import ai.core.server.web.AgentDefinitionWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AgentDefinitionModule extends Module {
    @Override
    protected void initialize() {
        bind(AgentDefinitionService.class);
        // LLMCallExecutor must be bound before ToolRegistryModule, which injects it
        // for resolving llm-call:{id} tool refs at agent runtime.
        bind(LLMCallExecutor.class);
        bind(JavaToSchemaService.class);
        bind(AgentDraftGenerator.class);
        bind(GenerateService.class);
        api().service(AgentDefinitionWebService.class, bind(AgentDefinitionWebServiceImpl.class));
    }
}
