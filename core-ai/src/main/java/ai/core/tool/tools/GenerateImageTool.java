package ai.core.tool.tools;

import ai.core.agent.AttachedContent;
import ai.core.agent.ExecutionContext;
import ai.core.media.MediaModelParameterHints;
import ai.core.media.MediaProvider;
import ai.core.media.domain.ImageData;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.media.domain.ImageGenerationResponse;
import ai.core.media.domain.MediaReference;
import ai.core.media.reference.ManagedReferenceProvider;
import ai.core.media.reference.MediaReferenceParser;
import ai.core.media.reference.SandboxMediaReferences;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.ImageDimensions;
import ai.core.utils.ImageDownscaler;
import core.framework.util.Strings;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

    /**
     * Generates images via the configured MediaProvider.
     *
     * @author stephen
     */
public final class GenerateImageTool extends ToolCall {
    public static final String TOOL_NAME = "generate_image";
    public static final String IMAGE_OUTPUT_SINK_CONTEXT_KEY = "__image_output_sink";

    private static final String ATTACHED_IMAGES = "attached";

    /**
     * Builds the tool description with the currently configured gateway image models appended,
     * so the agent knows which models exist and which model-specific parameters each accepts.
     */
    public static String buildDescription(List<MediaModelHint> imageModels) {
        return GenerateImageToolDescription.describe(imageModels, List.of());
    }

    /**
     * Text-to-image and image-to-image are separate gateway registrations (different endpoint_types),
     * so both lists are needed: listing only the text-to-image ones leaves the agent unable to name an
     * editing model, and it silently falls back to generating a new image from scratch.
     */
    public static String buildDescription(List<MediaModelHint> textToImageModels, List<MediaModelHint> imageToImageModels) {
        return GenerateImageToolDescription.describe(textToImageModels, imageToImageModels);
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
     * A status code alone gives the agent no next step. Login expiry and a missing gateway route are the
     * two failures a desktop user can actually fix; model and reference errors already carry the server's
     * own explanation. A custom endpoint (not a managed gateway) gets none of these hints: its 401 means
     * its own key, and its 404 does not mean a missing media gateway.
     */
    static String httpHint(String message, boolean managedGateway) {
        if (message == null || !managedGateway) return "";
        if (message.contains("HTTP 401")) {
            return "\n\nAuthentication failed. Sign in again from Hub, then retry.";
        }
        if (message.contains("HTTP 404")) {
            return "\n\nThe media gateway was not found on this server. Upgrade the server, or configure "
                    + "media.image.api.base / media.video.api.base to use a custom endpoint.";
        }
        return "";
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
                + " Convert it first — in the sandbox, e.g. `ffmpeg -i /tmp/original.jpg -frames:v 1 /tmp/fixed.jpg` for a camera JPEG, or `python3 -c \"from PIL import Image; Image.open('/tmp/original.jpg').convert('RGB').save('/tmp/fixed.jpg', quality=95)\"`, or on this machine without one —"
                + " then pass the converted file back as {\"sandbox_path\": \"/tmp/fixed.jpg\"}.";
    }

    private static String referencedImageNumber(String message) {
        var matcher = java.util.regex.Pattern.compile("Invalid image file or mode for image (\\d+)").matcher(message);
        return matcher.find() ? matcher.group(1) : "(the one the model rejected)";
    }

    /**
     * Upstream refuses a mask whose pixel size differs from an input image ("mask size does not match image
     * size") without naming any size, which sends the agent into one blind probe per attempt. The hint lists
     * the sizes actually sent and states the contract: the mask and every reference share one pixel size.
     */
    private static String maskSizeHint(String message, ImageGenerationRequest request) {
        if (message == null || request == null || !message.contains("mask size does not match")) return "";
        var sizes = new ArrayList<String>();
        var mask = request.mask();
        if (mask != null) {
            var size = ImageDimensions.ofBase64(mask.b64Json());
            if (size != null) sizes.add("mask is " + size.describe());
        }
        var images = request.inputImages() == null ? List.<MediaReference>of() : request.inputImages();
        for (var i = 0; i < images.size(); i++) {
            var size = ImageDimensions.ofBase64(images.get(i).b64Json());
            if (size != null) sizes.add("input_images[" + i + "] is " + size.describe());
        }
        return "\n\nMasked edit refused: the mask must have exactly the same pixel size as every input image"
                + (sizes.isEmpty() ? "" : " (" + String.join(", ", sizes) + ")")
                + " — rebuild the mask at that size and keep every reference on a canvas of the same size.";
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
        ImageGenerationRequest request = null;
        try {
            var args = parseArguments(arguments);
            var prompt = getStringValue(args, "prompt");
            if (Strings.isBlank(prompt)) return ToolCallResult.failed("prompt is required");

            var inputImages = inputImages(args, context, provider);
            var mask = mask(args, provider, context);
            var editing = inputImages != null && !inputImages.isEmpty() || mask != null;
            if (editing && !provider.acceptsImageReferences()) {
                return ToolCallResult.failed("Image generation failed: the configured media endpoint does not accept "
                        + "reference images; use a gateway image-to-image model or an endpoint that supports input_images/mask")
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            request = new ImageGenerationRequest(
                    imageModel(args, context, editing),
                    prompt,
                    parseInteger(args, "n"),
                    getStringValue(args, "size"),
                    getStringValue(args, "quality"),
                    getStringValue(args, "output_format"),
                    parseInteger(args, "output_compression"),
                    getStringValue(args, "background"),
                    inputImages,
                    mask,
                    getStringValue(args, "provider_extra"),
                    getStringValue(args, "previous_interaction_id"));

            var response = provider.generateImage(request);
            GenerateVideoTool.applySessionModelScope(context, args, "media.image.model");
            return imagesResult(context, response, getStringValue(args, "output_format"), startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Image generation failed: " + e.getMessage()
                            + httpHint(e.getMessage(), provider instanceof ManagedReferenceProvider)
                            + unreadableImageHint(e.getMessage())
                            + maskSizeHint(e.getMessage(), request), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult imagesResult(ExecutionContext context, ImageGenerationResponse response,
                                        String outputFormat, long startTime) {
        var images = response.data() == null ? List.<ImageData>of() : response.data();
        if (images.isEmpty()) {
            return ToolCallResult.completed("Image generation returned no images.")
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        if (images.size() == 1) {
            var notes = new ArrayList<>(response.notes());
            var output = imageOutput(context, images.getFirst(), outputFormat, notes);
            return ToolCallResult.completed(imageResult(output, response, notes))
                    .withDuration(System.currentTimeMillis() - startTime);
        }
        return ToolCallResult.completed(multipleImageResult(context, response, images, outputFormat))
                .withDuration(System.currentTimeMillis() - startTime);
    }

    private String multipleImageResult(ExecutionContext context, ImageGenerationResponse response,
                                       List<ImageData> images, String outputFormat) {
        var sb = new StringBuilder(256).append("Generated ").append(images.size()).append(" image(s).\n");
        var notes = new ArrayList<>(response.notes());
        for (var i = 0; i < images.size(); i++) {
            var output = imageOutput(context, images.get(i), outputFormat, notes);
            var rendered = isLinkable(output) ? "![Generated image " + (i + 1) + "](" + output + ")" : output;
            var revised = images.get(i).revisedPrompt() != null ? " (revised)" : "";
            sb.append("Image ").append(i + 1).append(": ").append(rendered).append(revised).append('\n');
        }
        if (response.mediaId() != null) sb.append("\nmedia_id (first image): ").append(response.mediaId()).append('\n');
        appendNotes(sb, notes);
        return sb.toString();
    }

    private boolean isLinkable(String output) {
        return output != null && (output.startsWith("http://") || output.startsWith("https://")
                || output.contains("/") || output.contains("\\"));
    }

    /**
     * URL results are downloaded only when the run has an artifact sink (desktop). A CLI run has no sink and
     * keeps the URL, exactly as before.
     */
    private String imageOutput(ExecutionContext context, ImageData image, String outputFormat, List<String> notes) {
        if (image.b64Json() != null) return GeneratedImageFiles.saveBase64(context, image.b64Json(), outputFormat);
        if (image.url() == null) return "(no URL)";
        if (!GeneratedImageFiles.hasSink(context)) return image.url();
        var saved = GeneratedImageFiles.saveUrl(context, image.url());
        if (saved != null) return saved;
        notes.add("could not download the generated image; the upstream URL was returned instead: " + image.url());
        return image.url();
    }

    /**
     * The media_id is the durable handle for this image; echoing it back is what lets a later edit name
     * the image instead of copying a 120-character URL back through the model.
     */
    private String imageResult(String output, ImageGenerationResponse response, List<String> notes) {
        var result = new StringBuilder(256)
                .append("Image generated. Include this exact Markdown image link in your final response:\n\n![Generated image](")
                .append(output).append(')');
        if (response.mediaId() != null) {
            result.append("\n\nmedia_id: ").append(response.mediaId())
                    .append("\nPass this media_id in input_images to edit or reuse this image later.");
        }
        if (response.interactionId() != null) result.append("\n\nprevious_interaction_id: ").append(response.interactionId());
        appendNotes(result, notes);
        return result.toString();
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
            return new MediaReference(null, ImageDownscaler.shrinkDataUri(content.data, mimeType));
        }
        return download(new MediaReference(content.url, null), true);
    }

    private MediaReference download(MediaReference reference, boolean shrinkImage) {
        var loaded = referenceImageLoader.load(reference.url());
        var mimeType = loaded.contentType() != null && !loaded.contentType().isBlank() ? loaded.contentType() : "image/png";
        var data = Base64.getEncoder().encodeToString(loaded.data());
        return new MediaReference(null,
                shrinkImage ? ImageDownscaler.shrinkDataUri(data, mimeType) : "data:" + mimeType + ";base64," + data);
    }

    private MediaReference mask(Map<String, Object> args, MediaProvider provider, ExecutionContext context) {
        var value = SandboxMediaReferences.expand(getStringValue(args, "mask"), "mask", context);
        return Strings.isBlank(value) ? null : resolve(MediaReferenceParser.parseItem(value, "mask"), provider, false);
    }

    /**
     * With a gateway-backed provider this is a no-op: the representation is chosen after routing, where
     * the destination provider is known, because a URL that suits one destination is unusable at
     * another. Talking to a provider directly there is no such stage, so the URL is downloaded here and
     * a media_id cannot be honoured at all.
     */
    private MediaReference resolve(MediaReference reference, MediaProvider provider) {
        return resolve(reference, provider, true);
    }

    private MediaReference resolve(MediaReference reference, MediaProvider provider, boolean shrinkImage) {
        if (provider instanceof ManagedReferenceProvider) return reference;
        if (reference.isSymbolic()) {
            throw new IllegalArgumentException("media_id references need the gateway media provider; pass a url or b64Json instead");
        }
        if (reference.b64Json() != null && !reference.b64Json().isBlank()) return reference;
        if (reference.url() == null || reference.url().isBlank()) {
            throw new IllegalArgumentException("input image requires a media_id, URL or base64 data");
        }
        return download(reference, shrinkImage);
    }

    /**
     * Image-to-image defaults to an editing model: the gateway rejects a text-to-image registration on the
     * image.edits endpoint. A session default set with model_scope=session wins when that model also edits.
     */
    private String imageModel(Map<String, Object> args, ExecutionContext context, boolean editing) {
        var requested = getStringValue(args, "model");
        if (requested != null && !requested.isBlank()) return requested;
        var sessionModel = configuredModel(context, "media.image.model");
        if (editing) {
            if (sessionModel != null && editModels(context).contains(sessionModel)) return sessionModel;
            var editDefault = configuredModel(context, "media.image.edit.model");
            if (editDefault != null) return editDefault;
        }
        return sessionModel != null ? sessionModel : configuredModel(context, "media.image.default.model");
    }

    private String configuredModel(ExecutionContext context, String key) {
        var model = context.getCustomVariables().get(key);
        return model instanceof String value && !value.isBlank() ? value : null;
    }

    private List<String> editModels(ExecutionContext context) {
        var value = context.getCustomVariables().get("media.image.edit.models");
        if (!(value instanceof List<?> models)) return List.of();
        var ids = new ArrayList<String>();
        for (var model : models) {
            if (model instanceof String id) ids.add(id);
        }
        return ids;
    }

    @Override
    public long getTimeoutMs() {
        // slow upstreams are synchronous here: the KIE provider polls a task for up to ten minutes (seedream
        // high-quality multi-reference renders run past five) and then downloads the results. Keep this above that
        // poll window, or the tool-level timeout fires first and discards the task id the provider error carries
        // (2026-10-04: a paid seedream task was abandoned at the old five-minute default).
        return 12 * 60 * 1000L;
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
            if (!customDescriptionSet) super.description(GenerateImageToolDescription.TOOL_DESC);
            // pure submit-and-wait I/O with no per-session in-flight state (unlike generate_video):
            // batches of image generations fan out in parallel instead of serializing ~90s each
            this.concurrencyGroup(ConcurrencyGroupType.MEDIA_GENERATION.getTypeName());
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "A detailed text description of the desired image").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "model", "The image generation model to use (uses the default if omitted); must be a model configured in the gateway — do not guess"),
                    ToolCallParameters.ParamSpec.of(String.class, "model_scope", "once (default) or session; session sets the model as the conversation default for subsequent calls, empty model clears it"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "n", "Number of images to generate (1-10, default 1)"),
                    ToolCallParameters.ParamSpec.of(String.class, "size", "Image dimensions as WxH, e.g. "
                            + MediaModelParameterHints.IMAGE_SIZE_PRESETS
                            + "; gpt-image-2/2.5 take custom frames on the 16-pixel grid (longest edge 3840)"),
                    ToolCallParameters.ParamSpec.of(String.class, "quality", "Optional output quality. For gpt-image-2 use only low, medium, high, or auto; gpt-image-2.5 also accepts xhigh and max, which cost many more output tokens. Omit it when no quality preference was requested; do not use standard or hd."),
                    ToolCallParameters.ParamSpec.of(String.class, "output_format", "Image format — png or jpeg"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "output_compression", "Optional JPEG compression level 0-100; only valid with output_format jpeg"),
                    ToolCallParameters.ParamSpec.of(String.class, "background", "Set to 'transparent' for transparent PNG backgrounds (requires output_format png)"),
                    ToolCallParameters.ParamSpec.of(String.class, "input_images", "Input images for image-to-image editing. Either the bare string \"attached\" — EVERY image attached to this message, use only when all of them are the subject — or a JSON ARRAY literal (square brackets required, e.g. [{\"media_id\":\"gateway-media-v1.img.abc\"}], [\"last\"], [{\"sandbox_path\":\"/workspace/attachments/a.jpg\"}] to name ONE attachment or a file you fixed locally, [{\"url\":\"https://...\"}] / [{\"b64Json\":\"data:...\"}] for external content); [] = no reference at all, omit for text-to-image. Send only the references that must be copied: an extra one changes the result and prompt wording cannot cancel it"),
                    ToolCallParameters.ParamSpec.of(String.class, "previous_interaction_id", "Gemini Interactions API ID to continue a multi-turn image edit"),
                    ToolCallParameters.ParamSpec.of(String.class, "mask", "Mask image for region editing: the model repaints the transparent part of the mask and keeps the rest, so pass it when only a region may change. A single reference object (NOT an array), same item shape as input_images, e.g. {\"sandbox_path\":\"/tmp/mask.png\"} or \"data:image/png;base64,...\". Must have exactly the same pixel dimensions as every input image (keep all references on canvases of one size)."),
                    ToolCallParameters.ParamSpec.of(String.class, "provider_extra", "Provider-specific JSON parameters")
            ));
            var tool = new GenerateImageTool(referenceImageLoader);
            build(tool);
            return tool;
        }
    }
}
