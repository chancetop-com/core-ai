package ai.core.example;

import ai.core.HuggingFaceModule;
import ai.core.MultiAgentModule;
import ai.core.example.controller.StopServiceController;
import ai.core.lsp.LanguageServerModule;
import core.framework.http.HTTPMethod;
import core.framework.module.App;
import core.framework.module.SystemModule;

/**
 * @author stephen
 */
public class ExampleApp extends App {
    @Override
    protected void initialize() {
        load(new SystemModule("sys.properties"));
        load(new MultiAgentModule());
        load(new HuggingFaceModule());
        load(new LanguageServerModule());
        load(new NaixtModule());
//        load(new ExampleModule());
        http().route(HTTPMethod.PUT, "/_app/stop-service", bind(StopServiceController.class));
    }
}
