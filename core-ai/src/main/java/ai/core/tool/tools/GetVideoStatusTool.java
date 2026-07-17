package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;

/**
 * Polls the status of an asynchronous video generation task.
 * The agent MUST call this after {@code generate_video} to check completion.
 * If still processing, a PENDING result is returned so the agent polls again.
 *
 * @author stephen
 */
public class GetVideoStatusTool extends ToolCall {
    public static final String TOOL_NAME = "get_video_status";

    private static final String TOOL_DESC = """
            Check the status of a previously submitted video generation task.
            Returns the current status and progress. If the video is still processing,
            poll again after a short wait. If completed, the result includes the video
            ID for reference.

            Parameters:
            - video_id (required): The video task ID returned by generate_video
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
            var videoId = getStringValue(args, "video_id");
            if (Strings.isBlank(videoId)) return ToolCallResult.failed("video_id is required");

            var status = provider.getVideoStatus(videoId);

            return switch (status.status()) {
                case "completed" -> ToolCallResult.completed(
                        "Video generation completed.\nvideo_id: " + videoId + "\n"
                                + "The video is ready. Use a follow-up action to present it to the user.")
                        .withDuration(System.currentTimeMillis() - startTime)
                        .withStats("video_id", videoId)
                        .withStats("status", "completed");
                case "failed" -> ToolCallResult.failed(
                        "Video generation failed: " + (status.error() != null ? status.error() : "unknown error"))
                        .withDuration(System.currentTimeMillis() - startTime)
                        .withStats("video_id", videoId)
                        .withStats("status", "failed");
                default -> {
                    // "processing", "queued", or any other non-terminal status
                    var progress = status.progress() != null ? status.progress() + "%" : "unknown";
                    yield ToolCallResult.pending(videoId,
                            "Video is still processing. Progress: " + progress
                                    + ". Poll again in 5-10 seconds.")
                            .withDuration(System.currentTimeMillis() - startTime)
                            .withStats("video_id", videoId)
                            .withStats("status", status.status())
                            .withStats("progress", status.progress());
                }
            };
        } catch (Exception e) {
            return ToolCallResult.failed("Video status check failed: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public ToolCallResult execute(String arguments) {
        return ToolCallResult.failed("get_video_status requires execution context");
    }

    public static class Builder extends ToolCall.Builder<Builder, GetVideoStatusTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public GetVideoStatusTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "video_id", "The video task ID returned by generate_video").required()
            ));
            var tool = new GetVideoStatusTool();
            build(tool);
            return tool;
        }
    }
}
