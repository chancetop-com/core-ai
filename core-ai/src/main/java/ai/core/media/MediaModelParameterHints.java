package ai.core.media;

/**
 * Model-family knowledge for media generation providers: maps an upstream model name to the
 * model-specific input parameters the agent may pass via provider_extra. Kept in sync with the
 * provider documentation (e.g. docs.kie.ai); unknown models return null and get no hint.
 *
 * @author stephen
 */
public final class MediaModelParameterHints {

    // KIE market slugs (seedream/5-pro-*, seedream/5-lite-*, seedream/4-5-*) take an aspect_ratio +
    // quality enum, unlike the OpenAI-style n/size parameters the hosted seedream endpoints expose
    private static final String KIE_SEEDREAM_HINT = "aspect_ratio (1:1/4:3/3:4/16:9/9:16/2:3/3:2/21:9, required); "
            + "quality (basic/high; 5.0 Lite also supports ultra); output_format (png/jpeg); "
            + "image_urls (image-to-image only, up to 10 images, use the -image-to-image model); "
            + "nsfw_checker (bool) via provider_extra; one image per task (n>1 is not supported)";

    private static final String SEEDANCE_2_HINT = "first_frame_url/last_frame_url (first/last-frame image-to-video); "
            + "reference_image_urls/reference_video_urls/reference_audio_urls (multimodal reference); "
            + "IMPORTANT: frame mode and reference mode are MUTUALLY EXCLUSIVE — when using "
            + "first_frame_url/last_frame_url, do NOT attach images or pass input_references, "
            + "and do NOT pass size (first/last-frame mode requires adaptive aspect ratio derived "
            + "from the frame image); "
            + "generate_audio (bool), resolution (480p/720p/1080p/4k), web_search (bool); "
            + "duration 4-15s (2.5 up to 30s); aspect_ratio 1:1/4:3/3:4/16:9/9:16/21:9/adaptive";

    private static final String SEEDANCE_1_5_HINT = "input_urls (image-to-video, up to 2 images); "
            + "aspect_ratio 1:1/21:9/4:3/3:4/16:9/9:16 (required); "
            + "duration 4-12s (integer, required); resolution 480p/720p/1080p; "
            + "fixed_lens (bool), generate_audio (bool)";

    private static final String MINIMAX_H3_TEXT_TO_VIDEO_HINT = "text-to-video (no reference images); "
            + "aspect_ratio REQUIRED: 21:9/16:9/4:3/1:1/3:4/9:16 — adaptive is NOT supported; "
            + "duration 4-15s (integer, required); resolution 768P/2K";

    private static final String MINIMAX_H3_IMAGE_TO_VIDEO_HINT = "image-to-video: "
            + "first_frame_url or last_frame_url REQUIRED (attach one image via input_references, "
            + "or two for first+last frame); duration 4-15s (integer, required); resolution 768P/2K";

    private static final String MINIMAX_H3_REFERENCE_TO_VIDEO_HINT = "reference-to-video: "
            + "reference_image_urls/reference_video_urls/reference_audio_urls (multimodal reference, "
            + "attach images via input_references; at least one reference required); "
            + "aspect_ratio adaptive/21:9/16:9/4:3/1:1/3:4/9:16; "
            + "duration 4-15s (integer, required); resolution 768P/2K";

    private static final String KLING_2_6_HINT = "image_urls (image-to-video, 1 image); "
            + "sound (bool), negative_prompt; duration \"5\"/\"10\"; aspect_ratio 1:1/16:9/9:16";

    private static final String KLING_3_HINT = "image_urls (first/last frame, up to 2); "
            + "sound (bool); duration \"3\"-\"15\"; aspect_ratio 16:9/9:16/1:1; "
            + "mode std/pro/4K; multi_shots (bool) + multi_prompt array for multi-shot; "
            + "kling_elements array (@element_name references in prompt, image/video/audio inputs)";

    private static final String KLING_V2_HINT = "image_url (single image) + tail_image_url (end frame); "
            + "duration \"5\"/\"10\"; negative_prompt; cfg_scale (0-1)";

    private static final String WAN_2_7_IMAGE_TO_VIDEO_HINT = "image-to-video: "
            + "first_frame_url/last_frame_url (frames), first_clip_url (video continuation), "
            + "driving_audio_url (audio); duration 2-15s (integer); "
            + "resolution 720p/1080p; prompt_extend (bool), watermark (bool), seed";

    private static final String WAN_2_7_R2V_HINT = "reference-to-video: "
            + "reference_image (≤5) / reference_video (≤5) — at least one required; "
            + "first_frame (single image), reference_voice (audio); "
            + "aspect_ratio 16:9/9:16/1:1/4:3/3:4; duration 2-10s (integer); "
            + "resolution 720p/1080p; prompt_extend (bool), watermark (bool), seed";

    private static final String WAN_2_7_TEXT_TO_VIDEO_HINT = "text-to-video (no reference images); "
            + "aspect_ratio 16:9/9:16/1:1/4:3/3:4; duration 2-15s (integer); "
            + "resolution 720p/1080p; prompt_extend (bool), watermark (bool), seed";

    private static final String WAN_LEGACY_HINT = "image_urls (image-to-video, 1 image); "
            + "duration \"5\"/\"10\"/\"15\"; resolution 720p/1080p; multi_shots (bool)";

    private static final String GROK_IMAGINE_IMAGE_TO_VIDEO_HINT = "image_urls (image-to-video); "
            + "mode fun/normal/spicy; duration \"6\"-\"30\"; resolution 480p/720p/1080p; "
            + "aspect_ratio 2:3/3:2/1:1/16:9/9:16";

    private static final String GROK_IMAGINE_1_5_HINT = "image_urls (up to 7); "
            + "aspect_ratio 1:1/16:9/9:16/3:2/2:3/auto; resolution 480p/720p/1080p; "
            + "duration 1-15s (integer)";

    private static final String GROK_IMAGINE_TEXT_TO_VIDEO_HINT = "text-to-video (no reference images); "
            + "resolution 480p/720p/1080p; duration 1-15s (integer); "
            + "aspect_ratio 1:1/16:9/9:16/3:2/2:3/auto";

    private static final String HAILUO_HINT = "image_url (single image required for image-to-video); "
            + "duration \"6\"/\"10\"; resolution 768P/1080P";

    private static final String PIXVERSE_HINT = "aspect_ratio 16:9/4:3/1:1/3:4/9:16/2:3/3:2/21:9; "
            + "duration 1-15s (integer); quality 360p/540p/720p/1080p";

    private static final String HAPPYHORSE_HINT = "resolution 720p/1080p; "
            + "aspect_ratio 16:9/9:16/1:1/4:3/3:4; duration 3-15s (integer); seed";

    private static final String BYTEDANCE_V1_HINT = "image_url (single image); "
            + "duration \"5\"/\"10\"; aspect_ratio 16:9/9:16/1:1/4:3/3:4";

    public static String videoHint(String upstreamModel) {
        if (upstreamModel == null) return null;
        if (upstreamModel.startsWith("bytedance/seedance-2")) return SEEDANCE_2_HINT;
        if (upstreamModel.startsWith("bytedance/seedance-1")) return SEEDANCE_1_5_HINT;
        if (upstreamModel.startsWith("minimax-h3/text-to-video")) return MINIMAX_H3_TEXT_TO_VIDEO_HINT;
        if (upstreamModel.startsWith("minimax-h3/image-to-video")) return MINIMAX_H3_IMAGE_TO_VIDEO_HINT;
        if (upstreamModel.startsWith("minimax-h3/")) return MINIMAX_H3_REFERENCE_TO_VIDEO_HINT;
        if (upstreamModel.startsWith("wan/2-7-image-to-video")) return WAN_2_7_IMAGE_TO_VIDEO_HINT;
        if (upstreamModel.startsWith("wan/2-7-r2v")) return WAN_2_7_R2V_HINT;
        if (upstreamModel.startsWith("wan/2-7-")) return WAN_2_7_TEXT_TO_VIDEO_HINT;
        if (upstreamModel.startsWith("wan/")) return WAN_LEGACY_HINT;
        if (upstreamModel.startsWith("kling-3.0/") || upstreamModel.startsWith("kling/v3-")) return KLING_3_HINT;
        if (upstreamModel.startsWith("kling-2.6/")) return KLING_2_6_HINT;
        if (upstreamModel.startsWith("kling/v2-")) return KLING_V2_HINT;
        if (upstreamModel.startsWith("grok-imagine-video-")) return GROK_IMAGINE_1_5_HINT;
        if (upstreamModel.startsWith("grok-imagine/text-to-video")) return GROK_IMAGINE_TEXT_TO_VIDEO_HINT;
        if (upstreamModel.startsWith("grok-imagine/")) return GROK_IMAGINE_IMAGE_TO_VIDEO_HINT;
        if (upstreamModel.startsWith("hailuo/")) return HAILUO_HINT;
        if (upstreamModel.startsWith("pixverse/")) return PIXVERSE_HINT;
        if (upstreamModel.startsWith("happyhorse")) return HAPPYHORSE_HINT;
        if (upstreamModel.startsWith("bytedance/v1-")) return BYTEDANCE_V1_HINT;
        if (upstreamModel.startsWith("gemini-omni")) {
            return "conversational video editing via previous_video_id; 10s duration cap; 720p max";
        }
        return null;
    }

    public static String imageHint(String upstreamModel) {
        if (upstreamModel == null) return null;
        if (upstreamModel.startsWith("gpt-image") || upstreamModel.startsWith("dall-e")) {
            return "quality (low/medium/high/auto), output_format (png/jpeg), output_compression, background (transparent)";
        }
        if (upstreamModel.startsWith("seedream/")) return KIE_SEEDREAM_HINT;
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
