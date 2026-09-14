package ai.core.server.gateway;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.media.domain.VideoGenerationResponse;
import ai.core.media.domain.VideoStatusResponse;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.MediaJob;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayMediaProviderTest {
    private static final String MODEL = "gpt-image-2.5-flare";

    @Test
    void imageEditOnAProtocolWithoutReferenceSupportFailsInsteadOfDroppingTheReference() {
        var routingEngine = mock(GatewayRoutingEngine.class);
        routeImageEdit(routingEngine, "OPENAI_COMPATIBLE");
        var gatewayMediaProvider = new GatewayMediaProvider(routingEngine, mock(GatewaySecretProtector.class),
                new MediaProviderAdapterFactory(), mock(MediaJobService.class), mock(MediaCostSettler.class));

        assertThatThrownBy(() -> gatewayMediaProvider.generateImage(editRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("OPENAI_COMPATIBLE");
    }

    @Test
    void imageEditKeepsItsReferenceOnAProtocolThatCarriesThem() {
        var routingEngine = mock(GatewayRoutingEngine.class);
        routeImageEdit(routingEngine, "OPENAI_IMAGES");
        var upstream = new RecordingMediaProvider();
        var gatewayMediaProvider = new GatewayMediaProvider(routingEngine, mock(GatewaySecretProtector.class),
                new StubAdapterFactory(upstream), mock(MediaJobService.class), mock(MediaCostSettler.class));

        gatewayMediaProvider.generateImage(editRequest());

        assertThat(upstream.imageRequest.inputImages()).hasSize(1);
    }

    @Test
    void videoReferencesAreNotAffectedByTheImageReferenceDeclaration() {
        var routingEngine = mock(GatewayRoutingEngine.class);
        when(routingEngine.hasEnabledProviders()).thenReturn(Boolean.TRUE);
        when(routingEngine.route("veo-3", GatewayEndpointType.VIDEO_GENERATION))
                .thenReturn(new GatewayRoute(provider("OPENAI_COMPATIBLE"), "veo-3"));
        var mediaJobService = mock(MediaJobService.class);
        var job = new MediaJob();
        job.id = "job-1";
        when(mediaJobService.createVideoJob(any(), any(), any(), any())).thenReturn(job);
        var upstream = new RecordingMediaProvider();
        var gatewayMediaProvider = new GatewayMediaProvider(routingEngine, mock(GatewaySecretProtector.class),
                new StubAdapterFactory(upstream), mediaJobService, mock(MediaCostSettler.class));

        gatewayMediaProvider.generateVideo(new VideoGenerationRequest("veo-3", "animate the reference", 5, null,
                List.of(new MediaReference(null, "data:image/png;base64,AAAA")), null, null));

        assertThat(upstream.videoRequest.inputReferences()).hasSize(1);
    }

    private void routeImageEdit(GatewayRoutingEngine routingEngine, String mediaProtocol) {
        when(routingEngine.hasEnabledProviders()).thenReturn(Boolean.TRUE);
        when(routingEngine.route(MODEL, GatewayEndpointType.IMAGE_EDIT))
                .thenReturn(new GatewayRoute(provider(mediaProtocol), MODEL));
    }

    private GatewayProviderConfig provider(String mediaProtocol) {
        var provider = new GatewayProviderConfig();
        provider.id = "provider-1";
        provider.name = "azure";
        provider.type = "openai-compatible";
        provider.baseUrl = "https://example.openai.azure.com";
        provider.mediaProtocol = mediaProtocol;
        return provider;
    }

    private ImageGenerationRequest editRequest() {
        return new ImageGenerationRequest(MODEL, "turn the lemon into a lime", null, null, null, null, null, null,
                List.of(new MediaReference(null, "data:image/png;base64,AAAA")), null, null, null);
    }

    private static final class StubAdapterFactory extends MediaProviderAdapterFactory {
        private final MediaProvider upstream;

        private StubAdapterFactory(MediaProvider upstream) {
            this.upstream = upstream;
        }

        @Override
        public MediaProvider create(GatewayProviderConfig provider, String apiKey, String googleCredentialsJson) {
            return upstream;
        }
    }

    private static final class RecordingMediaProvider implements MediaProvider {
        private ImageGenerationRequest imageRequest;
        private VideoGenerationRequest videoRequest;

        @Override
        public ImageGenerationResponse generateImage(ImageGenerationRequest request) {
            this.imageRequest = request;
            return new ImageGenerationResponse(List.of(), null);
        }

        @Override
        public VideoGenerationResponse generateVideo(VideoGenerationRequest request) {
            this.videoRequest = request;
            return new VideoGenerationResponse("upstream-video-1", "queued", null, null);
        }

        @Override
        public VideoStatusResponse getVideoStatus(String videoId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] downloadVideo(String videoId) {
            throw new UnsupportedOperationException();
        }
    }
}
