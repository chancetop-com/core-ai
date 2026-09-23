package ai.core.tool.tools;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.media.MediaModelParameterHints;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.reference.ManagedReferenceProvider;
import ai.core.media.reference.MediaReferenceParser;
import ai.core.media.reference.SandboxMediaReferences;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

    /**
     * Generates images via the configured MediaProvider.
     *
     * @author stephen
     */
public final class GenerateImageTool extends ToolCall {
    public static final String TOOL_NAME = "generate_image";
    public static final String IMAGE_OUTPUT_SINK_CONTEXT_KEY = "__image_output_sink";

    private static final String ATTACHED_IMAGES = "attached";

    private static final String TOOL_DESC = """
            Generate one or more images from a text prompt using the configured image generation model.

            IMAGE-TO-IMAGE — when the user is talking about an image that already exists (one they
            attached, or one an earlier generate_image call produced) and wants it changed, extended,
            restyled, or reused as a character/scene reference, edit that image instead of generating a
            new one from scratch: regenerating from the prompt alone loses the subject, style and
            composition the user asked you to keep.
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
                  camera photo, a crop, a mask): how you reference a SINGLE picture of several.
                - [{"url": "https://..."}] / [{"b64Json": "data:image/png;base64,..."}] — external content only.
              Square brackets are required even for a single item; [] = no reference at all.
              role is one of first_frame, last_frame, subject, scene, camera, style, prop, audio and decides which references
              are kept first if the model accepts fewer than you passed.
              Omit input_images entirely for plain text-to-image — several models reject references.
            - mask: Mask image for inpainting, in the same format as one input_images item
            - previous_interaction_id: Gemini Interactions API ID to continue a multi-turn image edit
            - provider_extra: JSON string with provider-specific parameters forwarded as-is

            The result provides a display-ready Markdown image link. In your final response, you MUST include that exact Markdown image link unchanged so the generated image is rendered inline. Do not merely say that the image was generated.
            """;

    /**
     * Builds the tool description with the currently configured gateway image models appended,
     * so the agent knows which models exist and which model-specific parameters each accepts.
     */
    public static String buildDescription(List<MediaModelHint> imageModels) {
        return buildDescription(imageModels, List.of());
    }

    /**
     * Text-to-image and image-to-image are separate gateway registrations (different endpoint_types),
     * so both lists are needed: listing only the text-to-image ones leaves the agent unable to name an
     * editing model, and it silently falls back to generating a new image from scratch.
     */
    public static String buildDescription(List<MediaModelHint> textToImageModels, List<MediaModelHint> imageToImageModels) {
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

    public static Builder builder() {
        return new Builder();
    }

    private static Integer parseInteger(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Reference trimming and unmentioned names are reported, never silent. */
    static void appendNotes(StringBuilder result, List<String> notes) {
        if (notes == null || notes.isEmpty()) return;
        result.append("\n\nNotes:");
        for (var note : notes) result.append("\n- ").append(note);
    }

    /**
     * Upstream refuses a reference image it cannot decode (a camera's multi-picture JPEG, HEIC, CMYK …) with a
     * message that names no cause and no fix, which sends the agent into blind retries. The hint points at the
     * sandbox copy of the attachment and at the reference form that takes a locally fixed file back in.
     */
    private static String unreadableImageHint(String message) {
        if (message == null || !message.contains("Invalid image file or mode")) return "";
        return "\n\nReference image " + referencedImageNumber(message)
                + " is not a standard PNG/JPEG/WEBP file (camera formats such as MPO or HEIC are refused as-is)."
                + " Convert it in the sandbox first — e.g. `ffmpeg -i /tmp/original.jpg -frames:v 1 /tmp/fixed.jpg`"
                + " for a camera JPEG, or `python3 -c \"from PIL import Image; Image.open('/tmp/original.jpg').convert('RGB').save('/tmp/fixed.jpg', quality=95)\"` —"
                + " then pass the converted file back as {\"sandbox_path\": \"/tmp/fixed.jpg\"}.";
    }

    private static String referencedImageNumber(String message) {
        var matcher = java.util.regex.Pattern.compile("Invalid image file or mode for image (\\d+)").matcher(message);
        return matcher.find() ? matcher.group(1) : "(the one the model rejected)";
    }

    private final GenerateVideoTool.ReferenceImageLoader referenceImageLoader;

    private GenerateImageTool(GenerateVideoTool.ReferenceImageLoader referenceImageLoader) {
        this.referenceImageLoader = referenceImageLoader;
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("generate_image requires execution context");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var startTime = System.currentTimeMillis();
        var provider = context.getImageMediaProvider();
        if (provider == null) return ToolCallResult.failed("no media provider configured");
        try {
            var args = parseArguments(arguments);
            var prompt = getStringValue(args, "prompt");
            if (Strings.isBlank(prompt)) return ToolCallResult.failed("prompt is required");

            var request = new ImageGenerationRequest(
                    getStringValue(args, "model") != null ? getStringValue(args, "model") : defaultModel(context),
                    prompt,
                    parseInteger(args, "n"),
                    getStringValue(args, "size"),
                    getStringValue(args, "quality"),
                    getStringValue(args, "output_format"),
                    parseInteger(args, "output_compression"),
                    getStringValue(args, "background"),
                    inputImages(args, context, provider),
                    mask(args, provider, context),
                    getStringValue(args, "provider_extra"),
                    getStringValue(args, "previous_interaction_id"));

            var response = provider.generateImage(request);
            GenerateVideoTool.applySessionModelScope(context, args, "media.image.model");

            if (response.data() != null && response.data().size() == 1) {
                return singleImageResult(context, response, getStringValue(args, "output_format"), startTime);
            }

            var sb = new StringBuilder(256);
            sb.append("Generated ").append(response.data() != null ? response.data().size() : 0).append(" image(s).\n");
            if (response.data() != null) {
                for (int i = 0; i < response.data().size(); i++) {
                    var img = response.data().get(i);
                    sb.append("Image ").append(i + 1).append(": ");
                    if (img.url() != null) sb.append(img.url());
                    else if (img.b64Json() != null) sb.append("[base64 encoded]");
                    if (img.revisedPrompt() != null) sb.append(" (revised)");
                    sb.append('\n');
                }
            }
            if (response.mediaId() != null) sb.append("media_id (first image): ").append(response.mediaId()).append('\n');
            appendNotes(sb, response.notes());
            return ToolCallResult.completed(sb.toString()).withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Image generation failed: " + e.getMessage() + unreadableImageHint(e.getMessage()), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    // model_scope=session mutates the session default model — keep those calls serial; plain generations fan out
    @Override
    public boolean isConcurrencySafe(String arguments) {
        try {
            var args = parseArguments(arguments);
            return !"session".equals(getStringValue(args, "model_scope"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ToolCallResult singleImageResult(ExecutionContext context, ImageGenerationResponse response, String outputFormat, long startTime) {
        var image = response.data().get(0);
        var output = image.b64Json() != null ? saveImage(context, image.b64Json(), outputFormat)
                : image.url() != null ? image.url() : "(no URL)";
        return ToolCallResult.completed(imageResult(output, response))
                .withDuration(System.currentTimeMillis() - startTime);
    }

    /**
     * The media_id is the durable handle for this image; echoing it back is what lets a later edit name
     * the image instead of copying a 120-character URL back through the model.
     */
    private String imageResult(String output, ImageGenerationResponse response) {
        var result = new StringBuilder(256)
                .append("Image generated. Include this exact Markdown image link in your final response:\n\n![Generated image](")
                .append(output).append(')');
        if (response.mediaId() != null) {
            result.append("\n\nmedia_id: ").append(response.mediaId())
                    .append("\nPass this media_id in input_images to edit or reuse this image later.");
        }
        if (response.interactionId() != null) result.append("\n\nprevious_interaction_id: ").append(response.interactionId());
        appendNotes(result, response.notes());
        return result.toString();
    }

    private List<MediaReference> inputImages(Map<String, Object> args, ExecutionContext context, MediaProvider provider) {
        var value = SandboxMediaReferences.expand(getStringValue(args, "input_images"), "input_images", context);
        if (Strings.isBlank(value)) return null;
        if (ATTACHED_IMAGES.equalsIgnoreCase(value.trim())) return attachedImages(context);
        return MediaReferenceParser.parse(value, "input_images").stream()
                .map(reference -> resolve(reference, provider))
                .toList();
    }

    /**
     * Image-to-image on the conversation's own attachments. Unlike generate_video this is opt-in
     * ({@code input_images="attached"}) instead of an implicit fallback: an attached photo must not
     * silently turn a plain text-to-image call into image-to-image, which text-to-image models
     * (e.g. seedream/5-pro-text-to-image) reject outright.
     */
    private List<MediaReference> attachedImages(ExecutionContext context) {
        var attachedContents = context.getAttachedContents();
        var references = attachedContents == null ? List.<MediaReference>of() : attachedContents.stream()
                .filter(content -> content.type == AttachedContent.AttachedContentType.IMAGE)
                .map(this::attachedReference)
                .toList();
        if (references.isEmpty()) throw new IllegalArgumentException("input_images=\"attached\" but no image is attached to this conversation");
        return references;
    }

    /**
     * Attachment URLs are this platform's own artifact URLs, not public ones, so they are inlined here
     * rather than handed to the resolver: an upstream that fetches URLs from the public internet cannot
     * reach them. Platform-generated media takes the media_id path instead, which the resolver can
     * represent per provider.
     */
    private MediaReference attachedReference(AttachedContent content) {
        if (content.isBase64()) {
            var mimeType = Strings.isBlank(content.mediaType) ? "image/png" : content.mediaType;
            return new MediaReference(null, "data:" + mimeType + ";base64," + content.data);
        }
        return download(new MediaReference(content.url, null));
    }

    private MediaReference download(MediaReference reference) {
        var loaded = referenceImageLoader.load(reference.url());
        var mimeType = loaded.contentType() != null && !loaded.contentType().isBlank() ? loaded.contentType() : "image/png";
        return new MediaReference(null, "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(loaded.data()));
    }

    private MediaReference mask(Map<String, Object> args, MediaProvider provider, ExecutionContext context) {
        var value = SandboxMediaReferences.expand(getStringValue(args, "mask"), "mask", context);
        return Strings.isBlank(value) ? null : resolve(MediaReferenceParser.parseItem(value, "mask"), provider);
    }

    /**
     * With a gateway-backed provider this is a no-op: the representation is chosen after routing, where
     * the destination provider is known, because a URL that suits one destination is unusable at
     * another. Talking to a provider directly there is no such stage, so the URL is downloaded here and
     * a media_id cannot be honoured at all.
     */
    private MediaReference resolve(MediaReference reference, MediaProvider provider) {
        if (provider instanceof ManagedReferenceProvider) return reference;
        if (reference.isSymbolic()) {
            throw new IllegalArgumentException("media_id references need the gateway media provider; pass a url or b64Json instead");
        }
        if (reference.b64Json() != null && !reference.b64Json().isBlank()) return reference;
        if (reference.url() == null || reference.url().isBlank()) {
            throw new IllegalArgumentException("input image requires a media_id, URL or base64 data");
        }
        return download(reference);
    }

    private String defaultModel(ExecutionContext context) {
        var model = context.getCustomVariables().get("media.image.model");
        return model instanceof String value && !value.isBlank() ? value : null;
    }

    private String saveImage(ExecutionContext context, String base64, String outputFormat) {
        var extension = "jpeg".equalsIgnoreCase(outputFormat) ? "jpeg" : "png";
        var fileName = "image-" + UUID.randomUUID() + "." + extension;
        var bytes = decodeImage(base64);
        var sink = context.getCustomVariable(IMAGE_OUTPUT_SINK_CONTEXT_KEY, ImageOutputSink.class);
        if (sink != null) return sink.save(fileName, "image/" + extension, bytes);

        var workspace = context.getCustomVariables().get("workspace");
        if (!(workspace instanceof String workspacePath) || workspacePath.isBlank()) {
            throw new BadRequestException("workspace is required to save the generated image");
        }
        var outputDirectory = Path.of(workspacePath).resolve(".core-ai").resolve("media").resolve("images");
        var outputPath = outputDirectory.resolve(fileName);
        try {
            Files.createDirectories(outputDirectory);
            Files.write(outputPath, bytes);
            return outputPath.toString();
        } catch (IOException e) {
            throw new RuntimeException("failed to save generated image: " + outputPath, e);
        }
    }

    private byte[] decodeImage(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("image provider returned invalid base64 data", "BAD_REQUEST", e);
        }
    }

    public interface ImageOutputSink {
        String save(String fileName, String contentType, byte[] bytes);
    }

    public static class Builder extends ToolCall.Builder<Builder, GenerateImageTool> {
        private GenerateVideoTool.ReferenceImageLoader referenceImageLoader = new GenerateVideoTool.HTTPReferenceImageLoader();
        // tracks whether a custom description was set; the parent field is kept in sync via super
        private boolean customDescriptionSet;

        Builder referenceImageLoader(GenerateVideoTool.ReferenceImageLoader referenceImageLoader) {
            this.referenceImageLoader = referenceImageLoader;
            return this;
        }

        @Override
        public Builder description(String description) {
            customDescriptionSet = true;
            super.description(description);
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public GenerateImageTool build() {
            this.name(TOOL_NAME);
            if (!customDescriptionSet) super.description(TOOL_DESC);
            // pure submit-and-wait I/O with no per-session in-flight state (unlike generate_video):
            // batches of image generations fan out in parallel instead of serializing ~90s each
            this.concurrencyGroup(ConcurrencyGroupType.MEDIA_GENERATION.getTypeName());
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "A detailed text description of the desired image").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "model", "The image generation model to use (uses the default if omitted); must be a model configured in the gateway — do not guess"),
                    ToolCallParameters.ParamSpec.of(String.class, "model_scope", "once (default) or session; session sets the model as the conversation default for subsequent calls, empty model clears it"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "n", "Number of images to generate (1-10, default 1)"),
                    ToolCallParameters.ParamSpec.of(String.class, "size", "Image dimensions, e.g. 1024x1024, 1792x1024, 1024x1792"),
                    ToolCallParameters.ParamSpec.of(String.class, "quality", "Optional output quality. For gpt-image-2 use only low, medium, high, or auto; gpt-image-2.5 also accepts xhigh and max, which cost many more output tokens. Omit it when no quality preference was requested; do not use standard or hd."),
                    ToolCallParameters.ParamSpec.of(String.class, "output_format", "Image format — png or jpeg"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "output_compression", "Optional JPEG compression level 0-100; only valid with output_format jpeg"),
                    ToolCallParameters.ParamSpec.of(String.class, "background", "Set to 'transparent' for transparent PNG backgrounds (requires output_format png)"),
                    ToolCallParameters.ParamSpec.of(String.class, "input_images", "Input images for image-to-image editing. Either the bare string \"attached\" — EVERY image attached to this message, use only when all of them are the subject — or a JSON ARRAY literal (square brackets required, e.g. [{\"media_id\":\"gateway-media-v1.img.abc\"}], [\"last\"], [{\"sandbox_path\":\"/workspace/attachments/a.jpg\"}] to name ONE attachment or a file you fixed locally, [{\"url\":\"https://...\"}] / [{\"b64Json\":\"data:...\"}] for external content); [] = no reference at all, omit for text-to-image. Send only the references that must be copied: an extra one changes the result and prompt wording cannot cancel it"),
                    ToolCallParameters.ParamSpec.of(String.class, "previous_interaction_id", "Gemini Interactions API ID to continue a multi-turn image edit"),
                    ToolCallParameters.ParamSpec.of(String.class, "mask", "Mask image for inpainting: a single reference object (NOT an array), same item shape as input_images, e.g. {\"sandbox_path\":\"/tmp/mask.png\"} or \"data:image/png;base64,...\""),
                    ToolCallParameters.ParamSpec.of(String.class, "provider_extra", "Provider-specific JSON parameters")
            ));
            var tool = new GenerateImageTool(referenceImageLoader);
            build(tool);
            return tool;
        }
    }
}
