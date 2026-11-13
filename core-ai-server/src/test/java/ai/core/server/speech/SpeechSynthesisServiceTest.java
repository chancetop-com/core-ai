package ai.core.server.speech;

import ai.core.api.server.speech.SynthesizeSpeechRequest;
import ai.core.media.audio.AudioProvider;
import ai.core.media.audio.SpeechSynthesisResult;
import ai.core.media.audio.TtsResult;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.GatewaySpeechSynthesisProvider;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.tools.MediaModelHint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SpeechSynthesisServiceTest {
    private final GatewayRoutingEngine routing = mock(GatewayRoutingEngine.class);
    private final GatewaySpeechSynthesisProvider gateway = mock(GatewaySpeechSynthesisProvider.class);
    private final SystemSettingsService settings = mock(SystemSettingsService.class);
    private final AudioProvider audio = mock(AudioProvider.class);

    @Test
    void followsTheConfiguredDefaultModel() {
        when(routing.explicitMediaModelHints(ai.core.server.gateway.GatewayEndpointType.AUDIO_SPEECH)).thenReturn(List.of(
                new MediaModelHint("tts-1", "tts-1", "openai"),
                new MediaModelHint("gpt-4o-mini-tts", "gpt-4o-mini-tts", "openai")));
        when(settings.speechSynthesisModel()).thenReturn("gpt-4o-mini-tts");

        var response = service().models();

        assertEquals("gpt-4o-mini-tts", response.defaultModel);
        assertEquals(2, response.models.size());
    }

    @Test
    void azureFallbackUsesTheSystemSpeechCredential() {
        when(settings.speechSynthesisModel()).thenReturn("azure-speech");
        when(settings.azureSpeechKey()).thenReturn("key");
        when(settings.azureSpeechRegion()).thenReturn("eastus");
        when(audio.synthesize(any())).thenReturn(new TtsResult(new byte[]{1, 2}, "audio/mpeg", List.of()));

        var request = new SynthesizeSpeechRequest();
        request.text = "hello";
        var result = service().synthesize(request);

        assertEquals("azure-speech", result.model());
        assertEquals("audio/mpeg", result.contentType());
        assertEquals(2, result.audio().length);
    }

    @Test
    void gatewaySynthesisReturnsModelAndProviderMetadata() {
        when(settings.speechSynthesisModel()).thenReturn("tts-1");
        when(gateway.synthesize(any())).thenReturn(new SpeechSynthesisResult(new byte[]{9}, "audio/mpeg", "alloy"));

        var request = new SynthesizeSpeechRequest();
        request.text = "hello";
        var result = service().synthesize(request);

        assertEquals("tts-1", result.model());
        assertEquals("alloy", result.voice());
        assertEquals(1, result.audio().length);
    }

    private SpeechSynthesisService service() {
        var service = new SpeechSynthesisService();
        service.routingEngine = routing;
        service.gatewaySpeechSynthesisProvider = gateway;
        service.settingsService = settings;
        service.audioProvider = audio;
        return service;
    }
}
