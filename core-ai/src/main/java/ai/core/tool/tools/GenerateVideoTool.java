package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.media.domain.VideoGenerationRequest;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;

import java.util.Map;

/**
 * Submits an asynchronous video generation request. Returns a pending task ID.
 * The agent should poll via {@code async_task_output} (which calls this tool's
 * built-in {@code poll()} method) to check completion. The manual
 * {@code get_video_status} tool is also available for explicit status checks.
 * <p>
 * Videos typically take 1–10 minutes to generate.
 *
 * @author stephen
 */
public class GenerateVideoTool extends ToolCall {
    public static final String TOOL_NAME = "generate_video";

    private static final String TOOL_DESC = """
            Generate a video from a text prompt. This is an asynchronous operation — the tool
            returns a task ID immediately, and the agent must poll get_video_status with that
            ID to check when the video is ready.

            IMPORTANT: After calling this tool, use get_video_status to poll for completion.
            Videos typically take 1–10 minutes to generate. Once the status is "completed",
            the download URL will be available in the status response.

            Parameters:
            - prompt (required): A detailed text description of the video scene
            - model: The video generation model to use (uses the default if omitted)
            - seconds: Video duration in seconds (model-specific, e.g. 5 or 10)
            - size: Video dimensions, e.g. "1280x720"
            - input_references: JSON string with reference images for video generation
            - provider_extra: JSON string with provider-specific parameters
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

            var request = new VideoGenerationRequest(
                    getStringValue(args, "model"),
                    prompt,
                    parseInteger(args, "seconds"),
                    getStringValue(args, "size"),
                    null, // inputReferences — not parsed from args for now
                    getStringValue(args, "provider_extra"));

            var response = provider.generateVideo(request);
            var videoId = response.id();

            var result = "Video generation submitted.\n"
                    + "video_id: " + videoId + "\n"
                    + "Videos typically take 1–10 minutes.";

            return ToolCallResult.pending(videoId, result)
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("video_id", videoId);
        } catch (Exception e) {
            return ToolCallResult.failed("Video generation failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public ToolCallResult poll(String taskId) {
        return VideoPollingHelper.poll(taskId);
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("generate_video requires execution context");
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

    public static class Builder extends ToolCall.Builder<Builder, GenerateVideoTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public GenerateVideoTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.timeoutMs(120_000L); // submit should be fast — video generation is async
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "prompt", "A detailed text description of the video scene").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "model", "The video generation model to use (uses the default if omitted)"),
                    ToolCallParameters.ParamSpec.of(Integer.class, "seconds", "Video duration in seconds (model-specific, e.g. 5 or 10)"),
                    ToolCallParameters.ParamSpec.of(String.class, "size", "Video dimensions, e.g. 1280x720"),
                    ToolCallParameters.ParamSpec.of(String.class, "input_references", "JSON string with reference image data"),
                    ToolCallParameters.ParamSpec.of(String.class, "provider_extra", "Provider-specific JSON parameters")
            ));
            var tool = new GenerateVideoTool();
            build(tool);
            return tool;
        }
    }
}
