package ai.core.tool.tools;

import ai.core.media.MediaModelParameterHints;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The generate_image prompt: the static tool description plus the currently configured gateway image
 * models, so the agent knows which models exist and which model-specific parameters each accepts.
 * Lives outside {@link GenerateImageTool} to keep that class within the file length limit.
 *
 * @author stephen
 */
final class GenerateImageToolDescription {

    // built at runtime so the description is not inlined into referencing class files (duplicated constant pools);
    // split in two because checkstyle counts a text block's lines against the method length limit
    static final String TOOL_DESC = editingGuidance() + parameterDocs();

    private static String editingGuidance() {
        return """
                Generate one or more images from a text prompt using the configured image generation model.

                IMAGE-TO-IMAGE — when the user is talking about an image that already exists (one they
                attached, or one an earlier generate_image call produced) and wants it changed, extended,
                restyled, or reused as a character/scene reference, edit that image instead of generating a
                new one from scratch: regenerating from the prompt alone loses the subject, style and
                composition the user asked you to keep.

                The change is made by this tool, with input_images — what it returns is the deliverable.
                Do NOT make it with image-processing code (PIL/OpenCV, ffmpeg, inpainting or matting
                models, register/tone-match/feather/paste scripts): code with no model behind it cannot draw
                what is not already in the pixels, and pasting a model render back into the original by hand
                leaves a soft, blurry patch — one call to this tool beats any pixel pipeline. Code may touch
                an image only for work that must be mechanical or exact (crop, resize, format conversion,
                montage, text overlay, converting a camera format before passing it here) or when the image
                itself came out of your own code (a chart, diagram or render you drew).

                "ONLY CHANGE THIS PART, LEAVE THE REST ALONE" is a mask, not a code composite: draw or
                pick the region, pass it in mask, and let the model repaint it — never fake it by pasting
                the model's output back into the original yourself. (Not every editing model accepts a mask;
                if this one refuses, use another.) A model edit re-renders the frame at the model's own
                output size: if the user demands the untouched areas stay pixel-identical, say plainly what
                a model edit can and cannot guarantee instead of hiding the difference behind code.

                  1. pass input_images — see below. To reuse an image THIS tool produced, pass its media_id
                     (or "last"). Never copy an image URL out of an earlier tool result: a media_id is
                     shorter, is checked against the caller, and lets the server hand each provider a form
                     of the image it can actually read.
                  2. pass a model marked [image-to-image] in the list below. Models marked only
                     [text-to-image] reject reference images and the call fails.
                  3. SEND EXACTLY THE REFERENCES THAT MUST BE COPIED, NOTHING ELSE. "attached" means EVERY image
                     of this message; when only some are involved, reference those one by one (their staged
                     sandbox path or media_id). A reference you do not want copied must not be sent: prompt
                     wording such as "ignore @image1" does not cancel it, an extra reference changes the result.
                  4. To generate with no reference at all — a new picture, or a polish that must not touch
                     the photographed subject — omit input_images or pass [].

                NAMING REFERENCES — when you pass more than one reference, give each a "name" and say in
                the prompt what that reference contributes, e.g. name "char_lin" with a prompt containing
                "@char_lin defines the woman's face and jacket only". The server rewrites @char_lin into
                whatever token the target model actually understands. Without a role sentence per
                reference the model guesses, and guesses wrong silently.

                """.stripIndent();
    }

    private static String parameterDocs() {
        return """
                For gpt-image-2, do not include the quality parameter unless the user explicitly requests a quality level. When requested, quality must be exactly one of: low, medium, high, or auto. gpt-image-2.5 additionally accepts xhigh and max, which spend many more image output tokens — use them only when the user asks for the highest quality. Never send standard or hd; they are invalid.

                Parameters:
                - prompt (required): A detailed text description of the desired image
                - model: Optional. The image model to use (model_id from the Configured image models
                  list below; each entry is tagged [text-to-image] and/or [image-to-image]). Omit to use
                  the default model (the session default if set, otherwise the system default). When
                  input_images is set the model MUST be one tagged [image-to-image]. Do NOT guess model names.
                - model_scope: Optional, "once" (default) or "session". "session" makes the model
                  the default for the rest of the conversation; pass model="" with
                  model_scope="session" to clear the session default and fall back to the system default.
                - n: Number of images to generate (1-10, default 1)
                - size: Image dimensions, e.g. "1024x1024", "1792x1024", "1024x1792"
                - quality: Optional output quality. For gpt-image-2, use only "low", "medium", "high", or "auto"; gpt-image-2.5 also accepts "xhigh" and "max", which cost many more output tokens. Omit it unless the user requests a quality preference. Never use "standard" or "hd".
                - output_format: Image format — "png" or "jpeg" (default depends on model)
                - output_compression: Optional JPEG compression level 0–100; only valid together with output_format "jpeg"
                - background: Set to "transparent" to generate PNGs with transparent backgrounds (requires output_format "png")
                - input_images: Input images for image-to-image editing (not all models support this).
                  Either the bare string "attached" — EVERY image attached to this message, use it only when all
                  of them are the subject — or a JSON ARRAY written out in full, whose items are:
                    - [{"media_id": "gateway-media-v1.img....", "name": "char_lin", "role": "subject"}] — an image
                      an earlier generate_image call returned. PREFERRED; ["last"] is the shorthand for the
                      most recent image of this conversation.
                    - [{"sandbox_path": "/workspace/attachments/a.jpg", "name": "dish"}] — one attachment of this
                      message (its staged path, listed in the message) or a file you made locally (a converted
                      camera photo, a crop, a mask) — without a sandbox, its own path on this machine.
                    - [{"url": "https://..."}] / [{"b64Json": "data:image/png;base64,..."}] — external content only.
                  Square brackets are required even for a single item; [] = no reference at all.
                  role is one of first_frame, last_frame, subject, scene, camera, style, prop, audio and decides which references
                  are kept first if the model accepts fewer than you passed.
                  Omit input_images entirely for plain text-to-image — several models reject references.
                - mask: Mask image for inpainting — the model repaints the transparent part of it and keeps
                  the rest; use it when only a region may change. Same shape as ONE input_images item (a
                  single object, not an array).
                - previous_interaction_id: Gemini Interactions API ID to continue a multi-turn image edit
                - provider_extra: JSON string with provider-specific parameters forwarded as-is

                The result provides a display-ready Markdown image link. In your final response, you MUST include that exact Markdown image link unchanged so the generated image is rendered inline. Do not merely say that the image was generated.
                """.stripIndent();
    }

    /**
     * Text-to-image and image-to-image are separate gateway registrations (different endpoint_types),
     * so both lists are needed: listing only the text-to-image ones leaves the agent unable to name an
     * editing model, and it silently falls back to generating a new image from scratch.
     */
    static String describe(List<MediaModelHint> textToImageModels, List<MediaModelHint> imageToImageModels) {
        var description = new StringBuilder(TOOL_DESC.length() + 512).append(TOOL_DESC);
        var generation = modelIds(textToImageModels);
        var editing = modelIds(imageToImageModels);
        var models = new LinkedHashMap<String, MediaModelHint>();
        for (var model : nullToEmpty(textToImageModels)) models.putIfAbsent(model.modelId(), model);
        for (var model : nullToEmpty(imageToImageModels)) models.putIfAbsent(model.modelId(), model);
        if (models.isEmpty()) return description.toString();

        description.append("\n\nConfigured image models (pass their model_id in the model parameter; "
                + "use model_scope=\"session\" to make it the default for the rest of the conversation):");
        for (var model : models.values()) {
            description.append("\n- ").append(model.modelId());
            if (model.providerName() != null) description.append(" (").append(model.providerName()).append(')');
            description.append(' ').append(capabilities(model.modelId(), generation, editing));
            var hint = MediaModelParameterHints.imageHint(model.upstreamModel());
            if (hint != null) description.append(": ").append(hint);
        }
        return description.toString();
    }

    private static String capabilities(String modelId, Set<String> generation, Set<String> editing) {
        if (generation.contains(modelId) && editing.contains(modelId)) return "[text-to-image, image-to-image]";
        return editing.contains(modelId) ? "[image-to-image]" : "[text-to-image]";
    }

    private static Set<String> modelIds(List<MediaModelHint> models) {
        return nullToEmpty(models).stream().map(MediaModelHint::modelId).collect(Collectors.toSet());
    }

    private static List<MediaModelHint> nullToEmpty(List<MediaModelHint> models) {
        return models == null ? List.of() : models;
    }

    private GenerateImageToolDescription() {
    }
}
