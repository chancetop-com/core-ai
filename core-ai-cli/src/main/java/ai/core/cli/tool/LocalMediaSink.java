package ai.core.cli.tool;

import ai.core.tool.tools.GenerateImageTool;
import ai.core.tool.tools.GetVideoStatusTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists generated media under {@code {workspace}/.core-ai/media}, the same location the CLI agent and the
 * desktop use, so a tool call and a chat turn leave the script's files in one place.
 *
 * @author stephen
 */
public class LocalMediaSink implements GenerateImageTool.ImageOutputSink, GetVideoStatusTool.VideoOutputSink {
    private final Path workspace;

    public LocalMediaSink(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public String save(String fileName, String contentType, byte[] bytes) {
        var video = contentType != null && contentType.startsWith("video/");
        var directory = workspace.resolve(video ? ".core-ai/media/videos" : ".core-ai/media/images");
        var target = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save generated media: " + target, e);
        }
        return target.toString();
    }
}
