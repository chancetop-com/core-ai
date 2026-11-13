package ai.core.server.gateway;

import ai.core.api.server.speech.SpeechTranscriptSegmentView;
import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.api.server.speech.SynthesizeSpeechRequest;
import ai.core.server.speech.SpeechSynthesisService;
import ai.core.server.speech.SpeechTranscriptionService;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.MultipartFile;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayAudioControllerTest {
    @Test
    void mapsOpenAiSpeechBodyToTheSpeechRequest() {
        var value = new LinkedHashMap<String, Object>();
        value.put("model", "tts-1");
        value.put("input", "hello");
        value.put("voice", "alloy");
        value.put("response_format", "wav");
        value.put("speed", 1.25);
        value.put("instructions", "cheerful");

        var request = new GatewayAudioController().speechRequest(value);

        assertThat(request.text).isEqualTo("hello");
        assertThat(request.model).isEqualTo("tts-1");
        assertThat(request.voice).isEqualTo("alloy");
        assertThat(request.format).isEqualTo("wav");
        assertThat(request.speed).isEqualTo(1.25);
        assertThat(request.instructions).isEqualTo("cheerful");
    }

    @Test
    void speechBodyAcceptsTextAsAnAliasForInput() {
        var request = new GatewayAudioController().speechRequest(Map.of("text", "hi"));

        assertThat(request.text).isEqualTo("hi");
    }

    @Test
    void verboseJsonCarriesOpenAiShape() {
        var view = view();

        var body = new GatewayAudioController().verboseJson(view);

        assertThat(body.get("task")).isEqualTo("transcribe");
        assertThat(body.get("language")).isEqualTo("zh-CN");
        assertThat(body.get("duration")).isEqualTo(3.75);
        assertThat(body.get("text")).isEqualTo("你好");
        var segments = (List<?>) body.get("segments");
        assertThat(segments).hasSize(1);
        var segment = (Map<?, ?>) segments.getFirst();
        assertThat(segment.get("id")).isEqualTo(0);
        assertThat(segment.get("start")).isEqualTo(0.19);
        assertThat(segment.get("end")).isEqualTo(3.75);
        assertThat(segment.get("text")).isEqualTo("你好");
    }

    @Test
    void subtitlesRenderSrtAndVttTimestamps() {
        var controller = new GatewayAudioController();

        var srt = controller.subtitles(view(), true);
        var vtt = controller.subtitles(view(), false);

        assertThat(srt).isEqualTo("1\n00:00:00,190 --> 00:00:03,750\n你好\n\n");
        assertThat(vtt).isEqualTo("WEBVTT\n\n00:00:00.190 --> 00:00:03.750\n你好\n\n");
    }

    @Test
    void transcribeReadsTheOpenAiFileFieldAndAnswersText() throws Exception {
        var audio = "wav-bytes".getBytes(StandardCharsets.UTF_8);
        Path file = Files.createTempFile("gateway-audio-test", ".wav");
        Files.write(file, audio);

        var controller = new GatewayAudioController();
        controller.transcriptionService = mock(SpeechTranscriptionService.class);
        controller.gatewayCallAudit = mock(GatewayCallAuditService.class);
        controller.webContext = mock(WebContext.class);
        doReturn("user-1").when(controller.webContext).get(AuthContext.USER_ID_KEY);
        when(controller.gatewayCallAudit.begin(any(), any(), any(), any(), any(), any())).thenReturn("call-1");
        when(controller.transcriptionService.transcribe(any(), any(), any(), any(), any())).thenReturn(view());

        var request = mock(Request.class);
        when(request.files()).thenReturn(Map.of("file", new MultipartFile(file, "voice.wav", "audio/wav")));
        var fields = new LinkedHashMap<String, String>();
        fields.put("model", "whisper-1");
        fields.put("language", "zh");
        fields.put("prompt", "names");
        fields.put("response_format", "text");
        when(request.formParams()).thenReturn(fields);
        when(request.header("x-session-id")).thenReturn(Optional.empty());

        Response response = controller.transcribe(request);

        assertThat(response.contentType().orElseThrow().mediaType).isEqualTo("text/plain");
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(controller.transcriptionService).transcribe(bytes.capture(), eq("audio/wav"), eq("whisper-1"), eq("zh"), eq("names"));
        assertThat(bytes.getValue()).isEqualTo(audio);
        verify(controller.gatewayCallAudit).finish(eq("call-1"), any(), any());
    }

    @Test
    void synthesizePassesTheMappedRequestToTheService() {
        var controller = new GatewayAudioController();
        controller.synthesisService = mock(SpeechSynthesisService.class);
        controller.gatewayCallAudit = mock(GatewayCallAuditService.class);
        controller.webContext = mock(WebContext.class);
        doReturn("user-1").when(controller.webContext).get(AuthContext.USER_ID_KEY);
        when(controller.gatewayCallAudit.begin(any(), any(), any(), any(), any(), any())).thenReturn("call-1");
        when(controller.synthesisService.synthesize(any())).thenReturn(
                new SpeechSynthesisService.SpeechSynthesis(new byte[]{1, 2, 3}, "audio/mpeg", "tts-1", "openai", "alloy"));

        var request = mock(Request.class);
        when(request.body()).thenReturn(Optional.of("""
                {"model":"tts-1","input":"hello","voice":"alloy","response_format":"mp3"}
                """.getBytes(StandardCharsets.UTF_8)));
        when(request.header("x-session-id")).thenReturn(Optional.empty());

        Response response = controller.synthesize(request);

        var speech = ArgumentCaptor.forClass(SynthesizeSpeechRequest.class);
        verify(controller.synthesisService).synthesize(speech.capture());
        assertThat(speech.getValue().text).isEqualTo("hello");
        assertThat(speech.getValue().format).isEqualTo("mp3");
        assertThat(response.contentType().orElseThrow().mediaType).isEqualTo("audio/mpeg");
        assertThat(response.header("x-speech-model")).contains("tts-1");
    }

    private SpeechTranscriptionView view() {
        var view = new SpeechTranscriptionView();
        view.text = "你好";
        view.language = "zh-CN";
        view.durationSeconds = 3.75;
        var segment = new SpeechTranscriptSegmentView();
        segment.text = "你好";
        segment.start = 0.19;
        segment.end = 3.75;
        view.segments = List.of(segment);
        return view;
    }
}
