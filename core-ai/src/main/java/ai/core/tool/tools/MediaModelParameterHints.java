package ai.core.tool.tools;

/**
 * Model-family knowledge for media generation providers: maps an upstream model name to the
 * model-specific input parameters the agent may pass via provider_extra. Kept in sync with the
 * provider documentation (e.g. docs.kie.ai); unknown models return null and get no hint.
 *
 * @author stephen
 */
public final class MediaModelParameterHints {

    public static String videoHint(String upstreamModel) {
        if (upstreamModel == null) return null;
        if (upstreamModel.startsWith("bytedance/seedance-2")) {
            return "first_frame_url/last_frame_url (first/last-frame image-to-video); "
                    + "reference_image_urls/reference_video_urls/reference_audio_urls (multimodal reference); "
                    + "IMPORTANT: frame mode and reference mode are MUTUALLY EXCLUSIVE — when using "
                    + "first_frame_url/last_frame_url, do NOT attach images or pass input_references, "
                    + "and do NOT pass size (first/last-frame mode requires adaptive aspect ratio derived "
                    + "from the frame image); "
                    + "generate_audio (bool), resolution (480p/720p/1080p/4k), web_search (bool); "
                    + "duration 4-15s (2.5 up to 30s); aspect_ratio 1:1/4:3/3:4/16:9/9:16/21:9";
        }
        if (upstreamModel.startsWith("gemini-omni")) {
            return "conversational video editing via previous_video_id; 10s duration cap; 720p max";
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

    public static String imageHint(String upstreamModel) {
        if (upstreamModel == null) return null;
        if (upstreamModel.startsWith("gpt-image") || upstreamModel.startsWith("dall-e")) {
            return "quality (low/medium/high/auto), output_format (png/jpeg), output_compression, background (transparent)";
        }
        if (upstreamModel.startsWith("seedream")) {
            return "n (1-4), size; negative prompt via provider_extra";
        }
        if (upstreamModel.startsWith("imagen")) {
            return "quality (standard/hd), size, style via provider_extra";
        }
        return null;
    }

    private MediaModelParameterHints() {
    }
}
