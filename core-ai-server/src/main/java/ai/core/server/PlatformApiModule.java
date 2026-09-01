package ai.core.server;

import ai.core.api.server.CapabilitiesWebService;
import ai.core.api.server.speech.SpeechWebService;
import ai.core.server.web.CapabilitiesWebServiceImpl;
import ai.core.server.web.SpeechWebServiceImpl;
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
        api().service(SpeechWebService.class, bind(SpeechWebServiceImpl.class));
    }

    private void registerCapabilities() {
        var impl = bind(CapabilitiesWebServiceImpl.class);
        impl.authDisabled = "true".equals(property("sys.auth.disabled").orElse("false"));
        impl.sandboxTerminalEnabled = Boolean.parseBoolean(property("sys.sandbox.terminal.enabled").orElse("false"));
        api().service(CapabilitiesWebService.class, impl);
    }
}
