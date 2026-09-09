package ai.core.server.render;

import ai.core.media.domain.MediaReference;

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

    /**
     * userId owns the resulting media job: without it the gateway cannot store the artifact and the generations page shows nothing.
     * References carry name / role / modality so the gateway can route a FIRST_FRAME to the model's frame slot and rewrite
     * {@code @name} mentions in the prompt into the model's own tokens — an unnamed URL list loses both.
     */
    record KeyframeRenderSpec(String userId, String model, String prompt, String size, List<MediaReference> references, String providerExtra) {
    }

    record ClipRenderSpec(String userId, String model, String prompt, Integer seconds, String size, List<MediaReference> references, String providerExtra) {
    }

    /** mediaId is the gateway handle of the media job that already stored this image, when there is one. */
    record KeyframeProduct(String url, String base64, String mediaId) {
    }

    /** state: processing | completed | failed */
    record ClipStatus(String state, Integer progress, String error) {
    }
}
