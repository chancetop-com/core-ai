package ai.core.server.gateway;

import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationResponse;
import core.framework.web.MultipartFile;
import core.framework.web.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayImageControllerTest {
    @Test
    void parsesOpenAiShapedImageRequestIncludingReferencesAndProviderExtra() {
        var request = new GatewayImageController().imageRequest("""
                {
                  "model": "gpt-image-2.5-flare",
                  "prompt": "turn the lemon into a lime",
                  "n": 1,
                  "size": "1024x1536",
                  "quality": "high",
                  "output_format": "png",
                  "output_compression": 90,
                  "background": "transparent",
                  "input_images": [{"media_id":"gateway-media-v1.img.am9iLTE","name":"source"}],
                  "mask": {"b64_json":"data:image/png;base64,AAAA"},
                  "provider_extra": {"input":{"watermark":false}},
                  "previous_interaction_id": "interaction-1"
                }
                """.getBytes(StandardCharsets.UTF_8), true);

        assertThat(request.model()).isEqualTo("gpt-image-2.5-flare");
        assertThat(request.prompt()).isEqualTo("turn the lemon into a lime");
        assertThat(request.n()).isEqualTo(1);
        assertThat(request.size()).isEqualTo("1024x1536");
        assertThat(request.outputCompression()).isEqualTo(90);
        assertThat(request.inputImages()).hasSize(1);
        assertThat(request.inputImages().getFirst().mediaId()).startsWith("gateway-media-v1.img");
        assertThat(request.mask().b64Json()).startsWith("data:image/png");
        assertThat(request.providerExtra()).contains("watermark");
        assertThat(request.previousInteractionId()).isEqualTo("interaction-1");
    }

    @Test
    void dispatchesImageGenerationThroughGatewayMediaProviderWithOwner() {
        var provider = mock(GatewayMediaProvider.class);
        var controller = new GatewayImageController();
        controller.gatewayMediaProvider = provider;
        var owner = new MediaJobOwner("user-1", null, null);
        when(provider.generateImage(org.mockito.ArgumentMatchers.any(), eq(owner)))
                .thenReturn(new ImageGenerationResponse(
                        List.of(new ImageData("aW1hZ2U=", null, "revised")), null, null,
                        "gateway-media-v1.img.am9iLTE", List.of()));

        controller.generate("""
                {"model":"gpt-image-2.5-flare","prompt":"a purple cat"}
                """.getBytes(StandardCharsets.UTF_8), owner, false);

        var request = ArgumentCaptor.forClass(ai.core.media.domain.ImageGenerationRequest.class);
        verify(provider).generateImage(request.capture(), eq(owner));
        assertThat(request.getValue().model()).isEqualTo("gpt-image-2.5-flare");
    }

    @Test
    void parsesOpenAiMultipartImageEdit() throws Exception {
        var image = Files.createTempFile("gateway-image-edit-", ".png");
        var mask = Files.createTempFile("gateway-image-mask-", ".png");
        Files.write(image, new byte[]{1, 2, 3});
        Files.write(mask, new byte[]{4, 5, 6});
        var request = mock(Request.class);
        when(request.formParams()).thenReturn(Map.of(
                "model", "gpt-image-2.5-flare", "prompt", "replace the sky", "n", "1", "size", "1024x1024"));
        when(request.files()).thenReturn(Map.of(
                "image[]", new MultipartFile(image, "source.png", "image/png"),
                "mask", new MultipartFile(mask, "mask.png", "image/png")));

        var parsed = new GatewayImageController().multipartImageRequest(request);

        assertThat(parsed.model()).isEqualTo("gpt-image-2.5-flare");
        assertThat(parsed.inputImages()).hasSize(1);
        assertThat(parsed.inputImages().getFirst().b64Json()).startsWith("data:image/png;base64,");
        assertThat(parsed.mask().b64Json()).startsWith("data:image/png;base64,");
    }

    @Test
    void rejectsInvalidImageRequestsBeforeCallingProvider() {
        var controller = new GatewayImageController();
        assertThatThrownBy(() -> controller.imageRequest("{}".getBytes(StandardCharsets.UTF_8), false))
                .isInstanceOf(core.framework.web.exception.BadRequestException.class)
                .hasMessageContaining("model");
        assertThatThrownBy(() -> controller.imageRequest(
                "{\"model\":\"gpt-image-2.5-flare\"}".getBytes(StandardCharsets.UTF_8), false))
                .isInstanceOf(core.framework.web.exception.BadRequestException.class)
                .hasMessageContaining("prompt");
        assertThatThrownBy(() -> controller.imageRequest("""
                {"model":"gpt-image-2.5-flare","prompt":"edit this"}
                """.getBytes(StandardCharsets.UTF_8), true))
                .isInstanceOf(core.framework.web.exception.BadRequestException.class)
                .hasMessageContaining("input_images or mask");
    }
}
