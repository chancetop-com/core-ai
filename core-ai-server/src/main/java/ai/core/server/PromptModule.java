package ai.core.server;

import ai.core.server.systemprompt.SystemPromptController;
import ai.core.server.systemprompt.SystemPromptService;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class PromptModule extends Module {
    @Override
    protected void initialize() {
        bind(SystemPromptService.class);

        var controller = bind(SystemPromptController.class);
        http().route(HTTPMethod.GET, "/api/system-prompts", controller::list);
        http().route(HTTPMethod.POST, "/api/system-prompts", controller::create);
        http().route(HTTPMethod.GET, "/api/system-prompts/:promptId", controller::get);
        http().route(HTTPMethod.PUT, "/api/system-prompts/:promptId", controller::update);
        http().route(HTTPMethod.DELETE, "/api/system-prompts/:promptId", controller::delete);
        http().route(HTTPMethod.GET, "/api/system-prompts/:promptId/versions", controller::versions);
        http().route(HTTPMethod.GET, "/api/system-prompts/:promptId/versions/:version", controller::getVersion);
        http().route(HTTPMethod.POST, "/api/system-prompts/:promptId/test", controller::test);
    }
}
