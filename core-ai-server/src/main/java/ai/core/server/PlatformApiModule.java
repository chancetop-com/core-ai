package ai.core.server;

import ai.core.api.server.CapabilitiesWebService;
import ai.core.api.server.speech.SpeechTranscriptSegmentView;
import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.api.server.speech.SpeechWebService;
import ai.core.server.gateway.GatewayAudioController;
import ai.core.server.speech.SpeechSynthesisController;
import ai.core.server.speech.SpeechSynthesisService;
import ai.core.server.speech.SpeechTranscriptionController;
import ai.core.server.speech.SpeechTranscriptionService;
import ai.core.server.web.CapabilitiesWebServiceImpl;
import ai.core.server.web.SpeechWebServiceImpl;
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
        // the transcription service must be bound before the WebService impl resolves its @Inject fields
        bind(SpeechTranscriptionService.class);
        bind(SpeechSynthesisService.class);
        api().service(SpeechWebService.class, bind(SpeechWebServiceImpl.class));
        // the raw controllers return beans/bytes directly; api() service registration does not cover them
        http().bean(SpeechTranscriptionView.class);
        http().bean(SpeechTranscriptSegmentView.class);
        http().route(HTTPMethod.POST, "/api/speech/transcriptions",
                bind(SpeechTranscriptionController.class)::transcribe);
        http().route(HTTPMethod.POST, "/api/speech/synthesis",
                bind(SpeechSynthesisController.class)::synthesize);
        // OpenAI-compatible audio surface for clients that point their SDK at /api/gateway/v1
        var gatewayAudio = bind(GatewayAudioController.class);
        http().route(HTTPMethod.POST, "/api/gateway/v1/audio/transcriptions", gatewayAudio::transcribe);
        http().route(HTTPMethod.POST, "/api/gateway/v1/audio/speech", gatewayAudio::synthesize);
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
