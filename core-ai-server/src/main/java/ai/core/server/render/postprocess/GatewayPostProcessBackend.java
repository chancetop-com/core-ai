package ai.core.server.render.postprocess;

import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.MediaReference;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gateway-backed post-processing: image edits go through image generation with input images;
 * descriptive video ops (Runway Aleph style) go through video generation with the source video on
 * provider_extra passthrough (upstream parameter names are V5 spike territory, same as references).
 *
 * @author stephen
 */
public class GatewayPostProcessBackend implements PostProcessBackend {
    @Inject
    MediaProvider mediaProvider;

    @Override
    public EditedImage editImage(String model, String instruction, List<String> inputImageUrls) {
        var references = inputImageUrls.stream().map(url -> new MediaReference(url, null)).toList();
        var response = mediaProvider.generateImage(new ImageGenerationRequest(
            model, instruction, 1, null, null, null, null, null, references, null, null, null));
        if (response.data() == null || response.data().isEmpty()) throw new BadRequestException("image edit returned no output");
        var image = response.data().getFirst();
        return new EditedImage(image.url(), image.b64Json());
    }

    @Override
    public String submitVideoOp(String model, String instruction, String inputVideoUrl, String providerExtra) {
        var extra = providerExtra != null ? providerExtra
            : JsonUtil.toJson(Map.of("input", Map.of("video_url", inputVideoUrl)));
        var response = mediaProvider.generateVideo(new VideoGenerationRequest(model, instruction, null, null, null, extra));
        return response.id();
    }

    @Override
    public VideoOpStatus pollVideoOp(String handleId) {
        var status = mediaProvider.getVideoStatus(handleId);
        var state = status.status() == null ? "processing" : status.status().toLowerCase(Locale.ROOT);
        if (!"completed".equals(state) && !"failed".equals(state)) state = "processing";
        return new VideoOpStatus(state, status.error());
    }

    @Override
    public byte[] downloadVideoOp(String handleId) {
        return mediaProvider.downloadVideo(handleId);
    }
}
