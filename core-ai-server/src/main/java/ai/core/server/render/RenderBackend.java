package ai.core.server.render;

import java.util.List;

/**
 * Render layer SPI: image and video generation with the async handle lifecycle callers need. The
 * only implementation is the gateway; the seam exists for a direct vendor SDK when an aggregator
 * cannot pass audio/video references through, not for local GPUs.
 *
 * @author stephen
 */
public interface RenderBackend {
    /** Image generation is synchronous on the gateway: the product URL or base64 comes back directly. */
    KeyframeProduct renderKeyframe(KeyframeRenderSpec spec);

    /** Video generation is asynchronous: returns an upstream handle to poll. */
    String submitClip(ClipRenderSpec spec);

    ClipStatus pollClip(String handleId);

    byte[] downloadClip(String handleId);

    /** userId owns the resulting media job: without it the gateway cannot store the artifact and the generations page shows nothing. */
    record KeyframeRenderSpec(String userId, String model, String prompt, String size, List<String> referenceImageUrls, String providerExtra) {
    }

    record ClipRenderSpec(String userId, String model, String prompt, Integer seconds, String size, List<String> referenceImageUrls, String providerExtra) {
    }

    /** mediaId is the gateway handle of the media job that already stored this image, when there is one. */
    record KeyframeProduct(String url, String base64, String mediaId) {
    }

    /** state: processing | completed | failed */
    record ClipStatus(String state, Integer progress, String error) {
    }
}
