package ai.core.server.speech;

import ai.core.api.server.speech.ListSpeechModelsResponse;
import ai.core.media.audio.AsrPhrase;
import ai.core.media.audio.AsrResult;
import ai.core.media.audio.AudioProvider;
import ai.core.media.audio.TranscriptSegment;
import ai.core.media.audio.TranscriptionException;
import ai.core.media.audio.TranscriptionResult;
import ai.core.media.audio.WordTimestamp;
import ai.core.server.gateway.GatewayEndpointType;
import ai.core.server.gateway.GatewayRoutingEngine;
import ai.core.server.gateway.GatewayTranscriptionProvider;
import ai.core.server.settings.SystemSettingsService;
import ai.core.tool.tools.MediaModelHint;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SpeechTranscriptionServiceTest {
    private final GatewayRoutingEngine routing = mock(GatewayRoutingEngine.class);
    private final GatewayTranscriptionProvider gateway = mock(GatewayTranscriptionProvider.class);
    private final SystemSettingsService settings = mock(SystemSettingsService.class);
    private final AudioProvider audio = mock(AudioProvider.class);

    @Test
    void listsGatewayModelsFirstAndAppendsAzureWhenConfigured() {
        when(routing.explicitMediaModelHints(GatewayEndpointType.AUDIO_TRANSCRIPTION)).thenReturn(List.of(
                new MediaModelHint("whisper-1", "whisper-1", "azure-openai", "Whisper"),
                new MediaModelHint("gpt-4o-transcribe", "gpt-4o-transcribe", "openai", null)));
        when(settings.azureSpeechKey()).thenReturn("key");
        when(settings.azureSpeechRegion()).thenReturn("eastus");

        ListSpeechModelsResponse response = service().models();

        assertEquals(List.of("whisper-1", "gpt-4o-transcribe", "azure-speech"),
                response.models.stream().map(model -> model.modelId).toList());
        assertEquals("whisper-1", response.defaultModel);
        assertEquals(Boolean.TRUE, response.azureConfigured);
        assertEquals("Whisper", response.models.getFirst().displayName);
    }

    @Test
    void blankModelRoutesToTheFirstGatewayModel() {
        when(routing.explicitMediaModelHints(GatewayEndpointType.AUDIO_TRANSCRIPTION)).thenReturn(List.of(
                new MediaModelHint("whisper-1", "whisper-1", "azure-openai", null)));
        when(gateway.transcribe(any())).thenReturn(new TranscriptionResult("hello", "en", 1.0,
                List.of(new TranscriptSegment("hello", 0, 1))));

        var view = service().transcribe(new byte[]{1}, "audio/wav", null, "en", null);

        var request = ArgumentCaptor.forClass(ai.core.media.audio.TranscriptionRequest.class);
        verify(gateway).transcribe(request.capture());
        assertEquals("whisper-1", request.getValue().model());
        assertEquals("hello", view.text);
        assertEquals("en", view.language);
        assertEquals("whisper-1", view.model);
        assertEquals("azure-openai", view.provider);
        assertEquals(1, view.segments.size());
    }

    @Test
    void azureFallbackMapsTwoLetterLanguageAndWordTimings() {
        when(settings.azureSpeechKey()).thenReturn("key");
        when(settings.azureSpeechRegion()).thenReturn("eastus");
        when(audio.transcribe(any())).thenReturn(new AsrResult("你好", List.of(
                new WordTimestamp("你", 0.2, 0.5), new WordTimestamp("好", 0.5, 0.9))));

        var view = service().transcribe(new byte[]{1}, "audio/wav", null, "zh", null);

        var spec = ArgumentCaptor.forClass(ai.core.media.audio.AsrSpec.class);
        verify(audio).transcribe(spec.capture());
        assertEquals("zh-CN", spec.getValue().locale());
        assertEquals("你好", view.text);
        assertEquals("zh-CN", view.language);
        assertEquals("azure-speech", view.model);
        assertEquals("Azure Speech", view.provider);
        assertEquals(0.9, view.durationSeconds);
        assertEquals(0.2, view.segments.getFirst().start);
    }

    @Test
    void azureFallbackKeepsSpeakerAndPhraseTimings() {
        when(settings.azureSpeechKey()).thenReturn("key");
        when(settings.azureSpeechRegion()).thenReturn("eastus");
        when(audio.transcribe(any())).thenReturn(new AsrResult("你好，欢迎。", List.of(),
                List.of(new AsrPhrase("你好，", "1", 0.1, 0.8),
                        new AsrPhrase("欢迎。", "2", 0.9, 1.7))));

        var view = service().transcribe(new byte[]{1}, "audio/wav", null, "zh", null);

        assertEquals(1.7, view.durationSeconds);
        assertEquals(2, view.segments.size());
        assertEquals("1", view.segments.getFirst().speaker);
        assertEquals(0.1, view.segments.getFirst().start);
        assertEquals("2", view.segments.getLast().speaker);
        assertEquals(1.7, view.segments.getLast().end);
    }

    @Test
    void explicitAzureModelSkipsTheGateway() {
        when(settings.azureSpeechKey()).thenReturn("key");
        when(settings.azureSpeechRegion()).thenReturn("eastus");
        when(audio.transcribe(any())).thenReturn(new AsrResult("text", List.of()));

        var view = service().transcribe(new byte[]{1}, "audio/wav", SpeechTranscriptionService.AZURE_SPEECH_MODEL,
                "en-US", null);

        verify(gateway, never()).transcribe(any());
        assertEquals("azure-speech", view.model);
        assertNull(view.durationSeconds);
        assertTrue(view.segments.isEmpty());
    }

    @Test
    void upstreamTranscriptionFailuresBecomeBadRequests() {
        when(routing.explicitMediaModelHints(GatewayEndpointType.AUDIO_TRANSCRIPTION)).thenReturn(List.of(
                new MediaModelHint("whisper-1", "whisper-1", "openai", null)));
        when(gateway.transcribe(any())).thenThrow(new TranscriptionException("HTTP 400: bad audio"));

        var error = assertThrows(BadRequestException.class,
                () -> service().transcribe(new byte[]{1}, "audio/wav", "whisper-1", null, null));

        assertTrue(error.getMessage().contains("bad audio"));
        assertEquals("SPEECH_UPSTREAM_FAILED", error.errorCode());
    }

    @Test
    void unconfiguredServerRejectsClearly() {
        var error = assertThrows(BadRequestException.class,
                () -> service().transcribe(new byte[]{1}, "audio/wav", null, null, null));

        assertTrue(error.getMessage().contains("not configured"));
        assertNull(service().models().defaultModel);
    }

    @Test
    void emptyAudioIsRejectedBeforeRouting() {
        assertThrows(BadRequestException.class,
                () -> service().transcribe(new byte[0], "audio/wav", null, null, null));
    }

    private SpeechTranscriptionService service() {
        var service = new SpeechTranscriptionService();
        service.routingEngine = routing;
        service.gatewayTranscriptionProvider = gateway;
        service.settingsService = settings;
        service.audioProvider = audio;
        return service;
    }
}
