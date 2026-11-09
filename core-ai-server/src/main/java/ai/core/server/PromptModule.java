package ai.core.server;

import ai.core.api.server.systemprompt.SystemPromptWebService;
import ai.core.server.systemprompt.MongoSystemPromptStore;
import ai.core.server.systemprompt.PgSystemPromptStore;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.systemprompt.SystemPromptStore;
import ai.core.server.systemprompt.SystemPromptWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class PromptModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(SystemPromptStore.class, bind(PgSystemPromptStore.class));
        } else {
            bind(SystemPromptStore.class, bind(MongoSystemPromptStore.class));
        }
        bind(SystemPromptService.class);
        api().service(SystemPromptWebService.class, bind(SystemPromptWebServiceImpl.class));
    }
}
