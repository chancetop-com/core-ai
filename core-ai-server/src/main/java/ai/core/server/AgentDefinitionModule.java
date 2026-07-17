package ai.core.server;

import ai.core.api.server.AgentDefinitionWebService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.AgentDraftGenerator;
import ai.core.server.agent.GenerateService;
import ai.core.server.agent.JavaToSchemaService;
import ai.core.server.web.AgentDefinitionWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AgentDefinitionModule extends Module {
    @Override
    protected void initialize() {
        bind(AgentDefinitionService.class);
        bind(JavaToSchemaService.class);
        bind(AgentDraftGenerator.class);
        bind(GenerateService.class);
        api().service(AgentDefinitionWebService.class, bind(AgentDefinitionWebServiceImpl.class));
    }
}
