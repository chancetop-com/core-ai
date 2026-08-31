package ai.core.server.render;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.List;
import java.util.Locale;

/**
 * The only RenderBackend: reuses the gateway media routing (KIE and friends). Audio and video
 * references ride on providerExtra passthrough, so whether they arrive upstream depends on the
 * provider rather than on this class.
 *
 * @author stephen
 */
public class GatewayRenderBackend implements RenderBackend {
    @Inject
    MediaProvider mediaProvider;

    @Override
    public KeyframeProduct renderKeyframe(KeyframeRenderSpec spec) {
        var response = mediaProvider.generateImage(new ImageGenerationRequest(
            spec.model(), spec.prompt(), 1, spec.size(), null, null, null, null,
            references(spec.referenceImageUrls()), null, spec.providerExtra(), null));
        if (response.data() == null || response.data().isEmpty()) throw new BadRequestException("image generation returned no output");
        var image = response.data().getFirst();
        return new KeyframeProduct(image.url(), image.b64Json());
    }

    @Override
    public String submitClip(ClipRenderSpec spec) {
        var response = mediaProvider.generateVideo(new VideoGenerationRequest(
            spec.model(), spec.prompt(), spec.seconds(), spec.size(), references(spec.referenceImageUrls()), spec.providerExtra()));
        return response.id();
    }

    @Override
    public ClipStatus pollClip(String handleId) {
        var status = mediaProvider.getVideoStatus(handleId);
        var state = status.status() == null ? "processing" : status.status().toLowerCase(Locale.ROOT);
        if (!"completed".equals(state) && !"failed".equals(state)) state = "processing";
        return new ClipStatus(state, status.progress(), status.error());
    }

    @Override
    public byte[] downloadClip(String handleId) {
        return mediaProvider.downloadVideo(handleId);
    }

    private List<MediaReference> references(List<String> urls) {
        if (urls == null || urls.isEmpty()) return null;
        return urls.stream().map(url -> new MediaReference(url, null)).toList();
    }
}
