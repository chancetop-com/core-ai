package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.media.domain.ImageGenerationRequest;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;

import java.util.Map;

/**
 * Generates images via the configured MediaProvider. Single images are returned as base64
 * in the tool response; multiple images return a summary with URLs/indices for the agent
 * to reference.
 *
 * @author stephen
 */
public class GenerateImageTool extends ToolCall {
    public static final String TOOL_NAME = "generate_image";

    private static final String TOOL_DESC = """
            Generate one or more images from a text prompt using the configured image generation model.

            Parameters:
            - prompt (required): A detailed text description of the desired image
            - model: The image generation model to use (uses the default if omitted)
            - n: Number of images to generate (1-10, default 1)
            - size: Image dimensions, e.g. "1024x1024", "1792x1024", "1024x1792"
            - quality: Output quality — "standard" or "hd"
            - output_format: Image format — "png" or "jpeg" (default depends on model)
            - output_compression: PNG compression level 0–9 where 0 is no compression
            - background: Set to "transparent" to generate PNGs with transparent backgrounds
            - input_images: Array of input image URLs for image-to-image generation (not all models support this)
            - provider_extra: JSON string with provider-specific parameters forwarded as-is

            Returns the generated image(s). For single images, the image is embedded inline.
            For multiple images, a text summary with indices is returned.
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        var startTime = System.currentTimeMillis();
        try {
            var provider = context.getMediaProvider();
            if (provider == null) throw new BadRequestException("no media provider configured");

            var args = parseArguments(arguments);
            var prompt = getStringValue(args, "prompt");
            if (Strings.isBlank(prompt)) return ToolCallResult.failed("prompt is required");

            var request = new ImageGenerationRequest(
                    getStringValue(args, "model"),
                    prompt,
                    parseInteger(args, "n"),
                    getStringValue(args, "size"),
                    getStringValue(args, "quality"),
                    getStringValue(args, "output_format"),
                    parseInteger(args, "output_compression"),
                    getStringValue(args, "background"),
                    null, // input_images — not parsed from args for now
                    null, // mask — not parsed from args for now
                    getStringValue(args, "provider_extra"));

            var response = provider.generateImage(request);

            if (response.data() != null && response.data().size() == 1) {
                var image = response.data().get(0);
                if (image.b64Json() != null) {
                    return ToolCallResult.completed(image.b64Json())
                            .withImage(image.b64Json(), "png")
                            .withDuration(System.currentTimeMillis() - startTime);
                }
                return ToolCallResult.completed("Image generated: " + (image.url() != null ? image.url() : "(no URL)"))
                        .withDuration(System.currentTimeMillis() - startTime);
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
            return ToolCallResult.completed(sb.toString()).withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Image generation failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private static Integer parseInteger(Map<String, Object> args, String key) {
        var val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    // Use the no-context execute as fallback (should never be called since tools always pass context)
    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("generate_image requires execution context");
    }

    public static class Builder extends ToolCall.Builder<Builder, GenerateImageTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public GenerateImageTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "A detailed text description of the desired image").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "model", "The image generation model to use (uses the default if omitted)"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "n", "Number of images to generate (1-10, default 1)"),
                    ToolCallParameters.ParamSpec.of(String.class, "size", "Image dimensions, e.g. 1024x1024, 1792x1024, 1024x1792"),
                    ToolCallParameters.ParamSpec.of(String.class, "quality", "Output quality — standard or hd"),
                    ToolCallParameters.ParamSpec.of(String.class, "output_format", "Image format — png or jpeg"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "output_compression", "PNG compression level 0-9 where 0 is no compression"),
                    ToolCallParameters.ParamSpec.of(String.class, "background", "Set to 'transparent' for transparent PNG backgrounds"),
                    ToolCallParameters.ParamSpec.of(String.class, "input_images", "JSON array of input image URLs for image-to-image generation"),
                    ToolCallParameters.ParamSpec.of(String.class, "mask", "Mask image URL for inpainting"),
                    ToolCallParameters.ParamSpec.of(String.class, "provider_extra", "Provider-specific JSON parameters")
            ));
            var tool = new GenerateImageTool();
            build(tool);
            return tool;
        }
    }
}
