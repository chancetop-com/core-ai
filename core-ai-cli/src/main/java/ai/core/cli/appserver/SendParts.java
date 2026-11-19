package ai.core.cli.appserver;

import ai.core.agent.AttachedContent;
import ai.core.utils.FileContents;
import ai.core.utils.ImageDownscaler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Parses {@code session/send} parts into message text plus LLM attachments. Paths must resolve inside
 * the workspace or the session staging directory; small text files are inlined as fenced blocks, images /
 * PDFs are attached as base64, and every other file (spreadsheets, archives, etc.) is referenced by path so
 * the agent opens it with its own tools instead of uploading its bytes to the model.
 *
 * @author stephen
 */
public final class SendParts {
    private static final long MAX_TEXT_BYTES = 100 * 1024;
    private static final long MAX_IMAGE_BYTES = 50 * 1024 * 1024;
    private static final long MAX_PDF_BYTES = 16 * 1024 * 1024;

    public static Parsed parse(ObjectNode params, Path workspace, Path stagingDir) {
        var parts = params.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw RpcException.invalidParams("parts must be a non-empty array");
        }
        var workspaceRoot = CanonicalPath.ofOrNormalized(workspace);
        var stagingRoot = CanonicalPath.ofOrNormalized(stagingDir);
        var text = new StringBuilder(256);
        var attachments = new ArrayList<AttachedContent>();
        for (JsonNode part : parts) {
            parsePart(part, workspaceRoot, stagingRoot, text, attachments);
        }
        return new Parsed(text.toString().strip(), attachments);
    }

    private static void parsePart(JsonNode part, Path workspaceRoot, Path stagingRoot,
                                  StringBuilder text, List<AttachedContent> attachments) {
        var type = part.path("type").asText("");
        switch (type) {
            case "text" -> text.append(part.path("text").asText("")).append('\n');
            case "file" -> text.append(fileContent(part, workspaceRoot, stagingRoot));
            case "image" -> attachments.add(binaryAttachment(part, workspaceRoot, stagingRoot,
                    AttachedContent.AttachedContentType.IMAGE, true));
            case "pdf" -> attachments.add(binaryAttachment(part, workspaceRoot, stagingRoot,
                    AttachedContent.AttachedContentType.PDF, false));
            default -> throw RpcException.invalidParams("unsupported part type: " + type);
        }
    }

    /**
     * Small text reaches the model inline (no tool round trip). A binary file — or text too large to
     * inline — stays on disk and the model gets its path, which keeps bytes it cannot read out of the
     * prompt and lets the agent open the file with whatever tool the format needs.
     */
    private static String fileContent(JsonNode part, Path workspaceRoot, Path stagingRoot) {
        var path = resolvePath(part, workspaceRoot, stagingRoot);
        try {
            long size = Files.size(path);
            if (size <= MAX_TEXT_BYTES) {
                var bytes = Files.readAllBytes(path);
                if (FileContents.looksLikeText(bytes)) {
                    return "\n```" + extension(path) + "\n" + new String(bytes, StandardCharsets.UTF_8) + "\n```\n";
                }
            }
            return fileReference(path, size);
        } catch (IOException e) {
            throw RpcException.business("PART_READ_FAILED", "failed to read file: " + e.getMessage(), e);
        }
    }

    private static String fileReference(Path path, long size) {
        return "\n[Attached file: " + path + " (" + sizeLabel(size) + ")]\n"
                + "The file is on local disk and its content is not included here; when the task needs it, "
                + "open that path with your tools (a shell command or a dedicated reader for binary formats, "
                + "read_file for text and images).\n";
    }

    private static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private static AttachedContent binaryAttachment(JsonNode part, Path workspaceRoot, Path stagingRoot,
                                                    AttachedContent.AttachedContentType contentType, boolean image) {
        var path = resolvePath(part, workspaceRoot, stagingRoot);
        try {
            long size = Files.size(path);
            var maxBytes = image ? MAX_IMAGE_BYTES : MAX_PDF_BYTES;
            if (size > maxBytes) {
                throw RpcException.business("PART_TOO_LARGE", "attachment is larger than " + maxBytes / (1024 * 1024) + "MB");
            }
            var data = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            var mediaType = mediaType(path);
            if (image) {
                var shrunk = ImageDownscaler.shrink(data, mediaType);
                data = shrunk.data();
                mediaType = shrunk.format();
            }
            var fileName = path.getFileName();
            return AttachedContent.ofBase64(data, mediaType, contentType, fileName == null ? "" : fileName.toString());
        } catch (IOException e) {
            throw RpcException.business("PART_READ_FAILED", "failed to read attachment: " + e.getMessage(), e);
        }
    }

    private static Path resolvePath(JsonNode part, Path workspaceRoot, Path stagingRoot) {
        var raw = part.path("path").asText("");
        if (raw.isBlank()) {
            throw RpcException.invalidParams("part.path is required");
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException e) {
            throw RpcException.invalidParams("invalid path: " + raw, e);
        }
        var resolved = path.isAbsolute() ? path.normalize() : workspaceRoot.resolve(path).normalize();
        var canonical = CanonicalPath.of(resolved);
        if (canonical == null
                || !(canonical.startsWith(workspaceRoot) || canonical.startsWith(stagingRoot))) {
            throw RpcException.business("PATH_NOT_ALLOWED",
                    "path must be inside the workspace or the session staging directory");
        }
        if (!Files.isRegularFile(canonical)) {
            throw RpcException.business("PART_NOT_FOUND", "file not found: " + raw, "path", raw);
        }
        return canonical;
    }

    private static String extension(Path path) {
        var file = path.getFileName();
        var name = file == null ? "" : file.toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static String mediaType(Path path) {
        return switch (extension(path).toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            default -> "image/jpeg";
        };
    }

    private SendParts() {
    }

    public record Parsed(String text, List<AttachedContent> attachments) {
        /** A send may carry attachments only (image / PDF with no typed text), like the web chat. */
        public boolean hasContent() {
            return !text.isBlank() || !attachments.isEmpty();
        }
    }
}
