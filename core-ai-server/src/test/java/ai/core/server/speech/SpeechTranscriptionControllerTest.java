package ai.core.server.speech;

import ai.core.api.server.speech.SpeechTranscriptionView;
import ai.core.server.gateway.GatewayCallAuditService;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.MultipartFile;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SpeechTranscriptionControllerTest {
    @Test
    void readsTheAudioFieldAndPassesFormOptions() throws Exception {
        var file = Files.createTempFile("speech-controller-", ".wav");
        Files.write(file, new byte[]{1, 2, 3, 4});
        var request = mock(Request.class);
        when(request.formParams()).thenReturn(Map.of("model", "whisper-1", "language", "zh-CN"));
        when(request.files()).thenReturn(Map.of("audio", new MultipartFile(file, "clip.wav", "audio/wav")));
        when(request.header("x-session-id")).thenReturn(Optional.of("session-1"));

        var service = mock(SpeechTranscriptionService.class);
        var audit = mock(GatewayCallAuditService.class);
        var webContext = mock(WebContext.class);
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        var view = new SpeechTranscriptionView();
        view.text = "hello";
        when(service.transcribe(any(), eq("audio/wav"), eq("whisper-1"), eq("zh-CN"), any()))
                .thenReturn(view);

        var controller = controller(service, audit, webContext);
        controller.transcribe(request);

        var audio = ArgumentCaptor.forClass(byte[].class);
        verify(service).transcribe(audio.capture(), eq("audio/wav"), eq("whisper-1"), eq("zh-CN"), eq(null));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, audio.getValue());
    }

    @Test
    void acceptsAnySingleFileField() throws Exception {
        var file = Files.createTempFile("speech-controller-", ".mp3");
        Files.write(file, new byte[]{9});
        var request = mock(Request.class);
        when(request.formParams()).thenReturn(Map.of());
        when(request.files()).thenReturn(Map.of("file", new MultipartFile(file, "clip.mp3", "audio/mpeg")));
        when(request.header("x-session-id")).thenReturn(Optional.empty());

        var service = mock(SpeechTranscriptionService.class);
        when(service.transcribe(any(), eq("audio/mpeg"), eq(null), eq(null), eq(null)))
                .thenReturn(new SpeechTranscriptionView());

        controller(service, mock(GatewayCallAuditService.class), mockWebContext()).transcribe(request);

        verify(service).transcribe(any(), eq("audio/mpeg"), eq(null), eq(null), eq(null));
    }

    @Test
    void rejectsARequestWithoutAudio() {
        var request = mock(Request.class);
        when(request.formParams()).thenReturn(Map.of());
        when(request.files()).thenReturn(Map.of());

        var error = assertThrows(BadRequestException.class,
                () -> controller(mock(SpeechTranscriptionService.class), mock(GatewayCallAuditService.class),
                        mockWebContext()).transcribe(request));

        assertEquals("audio file is required", error.getMessage());
    }

    private SpeechTranscriptionController controller(SpeechTranscriptionService service,
                                                     GatewayCallAuditService audit, WebContext webContext) {
        var controller = new SpeechTranscriptionController();
        controller.transcriptionService = service;
        controller.gatewayCallAudit = audit;
        controller.webContext = webContext;
        return controller;
    }

    private WebContext mockWebContext() {
        var webContext = mock(WebContext.class);
        when(webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        return webContext;
    }
}
