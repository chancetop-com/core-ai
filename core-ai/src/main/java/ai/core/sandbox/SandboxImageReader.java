package ai.core.sandbox;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.ReadFileTool;
import ai.core.utils.ImageFormats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The image branch a sandboxed read_file would otherwise miss: the runtime's read_file is a text reader, so
 * an image reaches the model as raw bytes in the transcript. The bytes are fetched through the sandbox file
 * endpoint instead and declared an image only when they really are one — the same rule the host tool applies.
 *
 * @author stephen
 */
public final class SandboxImageReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxImageReader.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    /** Past this size the payload is not worth decoding on the server; the agent downscales it in the sandbox. */
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /**
     * @return the image result, or null when this is not an image read the sandbox cannot answer itself — a
     *         different tool, a non-image path, bytes that turn out not to be an image, or a file the sandbox
     *         cannot serve — so the caller runs the regular sandbox tool.
     */
    public static ToolCallResult tryRead(String toolName, Map<String, Object> args, Sandbox sandbox) {
        if (!ReadFileTool.TOOL_NAME.equals(toolName)) return null;
        var filePath = ToolCall.getStringValue(args, "file_path");
        if (filePath == null || filePath.isBlank() || !isImagePath(filePath)) return null;

        var file = download(sandbox, filePath);
        if (file == null) return null;
        try {
            if (file.size() > MAX_IMAGE_BYTES) return tooLarge(filePath, file.size());
            return imageResult(filePath, Files.readAllBytes(file.path()));
        } catch (IOException e) {
            LOGGER.warn("sandbox image read failed, falling back to the runtime read_file: path={}, error={}", filePath, e.getMessage());
            return null;
        } finally {
            delete(file.path());
        }
    }

    private static SandboxFile download(Sandbox sandbox, String filePath) {
        try {
            return sandbox.downloadFile(filePath);
        } catch (RuntimeException e) {
            LOGGER.warn("sandbox file download failed, falling back to the runtime read_file: path={}, error={}", filePath, e.getMessage());
            return null;
        }
    }

    private static boolean isImagePath(String filePath) {
        var dot = filePath.lastIndexOf('.');
        if (dot < 0) return false;
        return IMAGE_EXTENSIONS.contains(filePath.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static ToolCallResult imageResult(String filePath, byte[] bytes) {
        var format = ImageFormats.detect(bytes);
        if (format == null) return null;
        if (!ImageFormats.isModelReadable(format)) {
            return ToolCallResult.failed("Error: " + filePath + " is a " + format.toUpperCase(Locale.ROOT)
                    + " image, which the model cannot read; convert it to " + ImageFormats.READABLE_FORMAT_LIST + " first");
        }
        return ToolCallResult.completed("Image file read successfully: " + filePath)
                .withImage(Base64.getEncoder().encodeToString(bytes), ImageFormats.mimeType(format))
                .withStats("filePath", filePath)
                .withStats("imageSize", bytes.length)
                .withStats("originalSize", bytes.length);
    }

    private static ToolCallResult tooLarge(String filePath, long size) {
        return ToolCallResult.failed("Error: " + filePath + " is " + (size / (1024 * 1024))
                + " MB, too large to send as an image; downscale it inside the sandbox first and read the result");
    }

    private static void delete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("failed to delete the downloaded sandbox file: path={}", path);
        }
    }

    private SandboxImageReader() {
    }
}
