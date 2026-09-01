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
        var terminalEnabledProperty = Boolean.parseBoolean(property("sys.sandbox.terminal.enabled").orElse("false"));
        var ticketSecretConfigured = !property("sys.sandbox.terminal.ticketSecret").orElse("").isBlank();
        var gatewayUrl = property("sys.sandbox.terminal.gatewayUrl").orElse("");
        // Never expose the secret value itself -- only whether it is configured -- and only
        // report the feature enabled when the ticket-signing secret AND the gateway URL are
        // both present, matching SandboxTerminalService's own gate so capabilities never claims
        // the feature is on when the service would actually reject every request.
        impl.sandboxTerminalEnabled = terminalEnabledProperty && ticketSecretConfigured && !gatewayUrl.isBlank();
        impl.sandboxTerminalGatewayUrl = gatewayUrl;
        api().service(CapabilitiesWebService.class, impl);
    }
}
