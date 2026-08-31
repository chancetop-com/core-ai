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

    /** Asynchronous video op (descriptive removal, lipsync): returns an upstream handle. */
    String submitVideoOp(String model, String instruction, String inputVideoUrl, String providerExtra);

    VideoOpStatus pollVideoOp(String handleId);

    byte[] downloadVideoOp(String handleId);

    record EditedImage(String url, String base64) {
    }

    /** state: processing | completed | failed */
    record VideoOpStatus(String state, String error) {
    }
}
