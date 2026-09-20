package ai.core.server.gateway;

import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayVideoControllerTest {
    @Test
    void parsesOpenAiShapedVideoRequestIncludingReferencesAndProviderExtra() {
        var request = new GatewayVideoController().videoRequest("""
                {
                  "model": "seedance-2.5",
                  "prompt": "make the cat dance",
                  "seconds": 5,
                  "size": "720x1280",
                  "input_references": [{"b64_json":"data:image/png;base64,AAAA","role":"first_frame"}],
                  "provider_extra": {"input":{"generate_audio":true}},
                  "previous_video_id": "gateway-media-v1.vid.cHJldmlvdXM"
                }
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(request.model()).isEqualTo("seedance-2.5");
        assertThat(request.prompt()).isEqualTo("make the cat dance");
        assertThat(request.seconds()).isEqualTo(5);
        assertThat(request.size()).isEqualTo("720x1280");
        assertThat(request.inputReferences()).hasSize(1);
        assertThat(request.inputReferences().getFirst().b64Json()).startsWith("data:image/png");
        assertThat(request.inputReferences().getFirst().role().name()).isEqualTo("FIRST_FRAME");
        assertThat(request.providerExtra()).contains("generate_audio");
        assertThat(request.previousInteractionId()).isEqualTo("gateway-media-v1.vid.cHJldmlvdXM");
    }

    @Test
    void dispatchesVideoOperationsThroughGatewayMediaProvider() {
        var provider = mock(GatewayMediaProvider.class);
        var controller = new GatewayVideoController();
        controller.gatewayMediaProvider = provider;
        var owner = new MediaJobOwner("user-1", null, null);
        when(provider.generateVideo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(owner)))
                .thenReturn(new VideoGenerationResponse("gateway-media-v1.vid.am9iLTE", "submitted", null, null));
        when(provider.getVideoStatus("gateway-media-v1.vid.am9iLTE", owner))
                .thenReturn(new VideoStatusResponse("gateway-media-v1.vid.am9iLTE", "running", 25, null, null));
        when(provider.downloadVideo("gateway-media-v1.vid.am9iLTE", owner)).thenReturn(new byte[]{0, 0, 0, 1});

        controller.generate("""
                {"model":"seedance-2.5","prompt":"dance","seconds":5}
                """.getBytes(StandardCharsets.UTF_8), owner);
        controller.status("gateway-media-v1.vid.am9iLTE", owner);
        controller.content("gateway-media-v1.vid.am9iLTE", owner);

        var request = ArgumentCaptor.forClass(ai.core.media.domain.VideoGenerationRequest.class);
        verify(provider).generateVideo(request.capture(), org.mockito.ArgumentMatchers.eq(owner));
        assertThat(request.getValue().model()).isEqualTo("seedance-2.5");
        verify(provider).getVideoStatus("gateway-media-v1.vid.am9iLTE", owner);
        verify(provider).downloadVideo("gateway-media-v1.vid.am9iLTE", owner);
    }

    @Test
    void rejectsVideoRequestsWithoutModelOrPromptBeforeCallingAProvider() {
        var controller = new GatewayVideoController();
        assertThatThrownBy(() -> controller.videoRequest("{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(core.framework.web.exception.BadRequestException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> controller.videoRequest("{\"model\":\"seedance-2.5\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(core.framework.web.exception.BadRequestException.class)
                .hasMessageContaining("prompt");
    }
}
