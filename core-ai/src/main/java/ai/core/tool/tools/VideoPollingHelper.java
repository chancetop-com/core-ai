package ai.core.tool.tools;

import ai.core.media.MediaProvider;
import ai.core.tool.ToolCallResult;

/**
 * Static holder so {@link GenerateVideoTool#poll(String)} can access the
 * {@link MediaProvider} without {@code ExecutionContext} (which is unavailable
 * during the async poll lifecycle).
 * <p>
 * Set once at server startup via {@code GatewayModule}, then read by the tool's
 * {@code poll()} implementation.
 *
 * @author stephen
 */
public final class VideoPollingHelper {
    private static volatile MediaProvider provider;

    private VideoPollingHelper() {
    }

    public static void setProvider(MediaProvider provider) {
        VideoPollingHelper.provider = provider;
    }

    public static ToolCallResult poll(String videoId) {
        var p = provider;
        if (p == null) return ToolCallResult.failed("media provider not configured — video polling unavailable");
        try {
            var status = p.getVideoStatus(videoId);
            return switch (status.status()) {
                case "completed" -> ToolCallResult.completed(
                        "Video " + videoId + " is ready. The video generation has completed successfully.");
                case "failed" -> ToolCallResult.failed(
                        "Video generation failed: " + (status.error() != null ? status.error() : "unknown error"));
                default -> {
                    var progress = status.progress() != null ? status.progress() + "%" : "unknown";
                    yield ToolCallResult.pending(videoId,
                            "Video is still processing (progress: " + progress + "). Poll again.");
                }
            };
        } catch (Exception e) {
            return ToolCallResult.failed("Video polling failed: " + e.getMessage(), e);
        }
    }
}
