package ai.core.tool.tools;

/**
 * Model-family knowledge for video generation providers: maps an upstream model name to the
 * model-specific input parameters the agent may pass via provider_extra. Kept in sync with the
 * provider documentation (e.g. docs.kie.ai); unknown models return null and get no hint.
 *
 * @author stephen
 */
public final class VideoModelParameterHints {

    public static String hint(String upstreamModel) {
        if (upstreamModel == null) return null;
        if (upstreamModel.startsWith("bytedance/seedance-2")) {
            return "first_frame_url/last_frame_url (first/last-frame image-to-video); "
                    + "reference_image_urls/reference_video_urls/reference_audio_urls (multimodal reference, mutually exclusive with frames); "
                    + "generate_audio (bool), resolution (480p/720p/1080p/4k), web_search (bool); "
                    + "duration 4-15s (2.5 up to 30s); aspect_ratio 1:1/4:3/3:4/16:9/9:16/21:9";
        }
        if (upstreamModel.startsWith("kling/")) {
            return "sound (bool), negative_prompt, cfg_scale; duration \"5\"/\"10\"; aspect_ratio 1:1/16:9/9:16";
        }
        if (upstreamModel.startsWith("wan/")) {
            return "image_urls (image-to-video), resolution 720p/1080p; duration \"5\"/\"10\"/\"15\"";
        }
        if (upstreamModel.startsWith("grok-imagine/")) {
            return "image_urls (image-to-video); duration and resolution per model";
        }
        if (upstreamModel.startsWith("minimax/")) {
            return "image_urls (image-to-video); duration and resolution per model";
        }
        return null;
    }

    private VideoModelParameterHints() {
    }
}
