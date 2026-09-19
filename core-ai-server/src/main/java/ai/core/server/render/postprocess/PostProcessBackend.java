package ai.core.server.render.postprocess;

import java.util.List;

/**
 * Post-process SPI: everything rides existing APIs — image edit and descriptive video repair are
 * already aggregated on the gateway (KIE: Nano Banana Pro / Seedream / Runway Aleph); upscale and
 * lipsync vendors plug in as additional implementations without touching callers.
 *
 * @author stephen
 */
public interface PostProcessBackend {
    /** Synchronous image edit: returns the edited image (url or base64). */
    EditedImage editImage(String model, String instruction, List<String> inputImageUrls);

    /**
     * Asynchronous video op (descriptive removal, lipsync, delivery upsample): returns an upstream handle.
     * {@code size} is the target geometry in the OpenAI {@code WxH} form the video models speak; the gateway
     * derives the vendor's ratio/resolution tier from it, null keeps the provider's default.
     */
    String submitVideoOp(String model, String instruction, String inputVideoUrl, String size, String providerExtra);

    VideoOpStatus pollVideoOp(String handleId);

    byte[] downloadVideoOp(String handleId);

    record EditedImage(String url, String base64) {
    }

    /** state: processing | completed | failed */
    record VideoOpStatus(String state, String error) {
    }
}
