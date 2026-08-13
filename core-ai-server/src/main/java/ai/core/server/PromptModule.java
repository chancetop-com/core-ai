package ai.core.server;

import ai.core.api.server.systemprompt.SystemPromptWebService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.systemprompt.SystemPromptWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class PromptModule extends Module {
    @Override
    protected void initialize() {
        bind(SystemPromptService.class);
        api().service(SystemPromptWebService.class, bind(SystemPromptWebServiceImpl.class));
    }
}
