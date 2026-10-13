package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.session.CustomEventEmitter;
import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GetVideoStatusTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Local media sink for the desktop: persists generated images/videos under {@code {workspace}/.core-ai/media}
 * and publishes an {@code artifact} custom event so the desktop panel can show the new artifact live. The
 * returned path matches the no-sink fallback exactly, so the tool result text never changes. The event
 * emission is best-effort — it must never fail the generation.
 *
 * @author stephen
 */
public class LocalArtifactSink implements GenerateImageTool.ImageOutputSink, GetVideoStatusTool.VideoOutputSink {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalArtifactSink.class);

    /**
     * The two sink interfaces share {@code save(fileName, contentType, bytes)} and the execution context has
     * no current tool name, so the source tool is inferred from the content type and the fallback file name.
     */
    private static String inferTool(String fileName, String contentType) {
        if (contentType != null && contentType.startsWith("video/")) {
            return "get_video_status";
        }
        if (fileName != null && fileName.startsWith("tool-image-")) {
            return "tool-image";
        }
        return "generate_image";
    }

    private final Path workspace;
    private final ExecutionContext context;

    public LocalArtifactSink(Path workspace, ExecutionContext context) {
        this.workspace = workspace;
        this.context = context;
    }

    @Override
    public String save(String fileName, String contentType, byte[] bytes) {
        boolean video = contentType != null && contentType.startsWith("video/");
        var relative = video ? ".core-ai/media/videos" : ".core-ai/media/images";
        var directory = workspace.resolve(relative);
        var target = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new RuntimeException("failed to save artifact: " + target, e);
        }
        publish(fileName, contentType, bytes.length, relative + "/" + fileName);
        return target.toString();
    }

    private void publish(String name, String contentType, int size, String relativePath) {
        try {
            var emitter = context.getCustomVariable(CustomEventEmitter.CONTEXT_KEY, CustomEventEmitter.class);
            if (emitter == null) {
                return;
            }
            var payload = Params.object();
            payload.put("path", relativePath);
            payload.put("kind", contentType != null && contentType.startsWith("video/") ? "video" : "image");
            payload.put("name", name);
            payload.put("contentType", contentType);
            payload.put("size", size);
            payload.put("tool", inferTool(name, contentType));
            payload.put("createdAt", Instant.now().toString());
            var reason = emitter.emit("artifact", payload.toString(), null, context.getCurrentToolCallId());
            if (reason != null) {
                LOGGER.warn("artifact event rejected, path={}, reason={}", relativePath, reason);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to publish artifact event, path={}, error={}", relativePath, e.getMessage());
        }
    }
}
