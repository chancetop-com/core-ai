package ai.core.server;

import ai.core.server.web.CapabilitiesController;
import ai.core.server.web.SpeechController;
import core.framework.http.HTTPMethod;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class PlatformApiModule extends Module {
    @Override
    protected void initialize() {
        configureSpeechToken();
        registerCapabilities();
    }

    private void configureSpeechToken() {
        var speechController = bind(SpeechController.class);
        speechController.speechKey = property("azure.speech.key").orElse(null);
        speechController.speechRegion = property("azure.speech.region").orElse("eastus");
        speechController.speechEndpoint = property("azure.speech.endpoint").orElse(null);
        http().route(HTTPMethod.GET, "/api/speech/token", speechController::getToken);
    }

    private void registerCapabilities() {
        var controller = bind(CapabilitiesController.class);
        controller.authDisabled = "true".equals(property("sys.auth.disabled").orElse("false"));
        http().route(HTTPMethod.GET, "/api/capabilities", controller::get);
    }
}
