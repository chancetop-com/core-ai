package ai.core.cli.appserver;

import ai.core.agent.AttachedContent;
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
 * the workspace or the session staging directory; small text files are inlined as fenced blocks,
 * images / PDFs are attached as base64.
 *
 * @author stephen
 */
public final class SendParts {
    private static final long MAX_TEXT_BYTES = 100 * 1024;
    private static final long MAX_BINARY_BYTES = 16 * 1024 * 1024;

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
            case "file" -> text.append(inlineFile(part, workspaceRoot, stagingRoot));
            case "image" -> attachments.add(binaryAttachment(part, workspaceRoot, stagingRoot,
                    AttachedContent.AttachedContentType.IMAGE));
            case "pdf" -> attachments.add(binaryAttachment(part, workspaceRoot, stagingRoot,
                    AttachedContent.AttachedContentType.PDF));
            default -> throw RpcException.invalidParams("unsupported part type: " + type);
        }
    }

    private static String inlineFile(JsonNode part, Path workspaceRoot, Path stagingRoot) {
        var path = resolvePath(part, workspaceRoot, stagingRoot);
        try {
            long size = Files.size(path);
            if (size > MAX_TEXT_BYTES) {
                throw RpcException.business("PART_TOO_LARGE", "file is larger than 100KB, use the read_file tool instead");
            }
            var content = Files.readString(path, StandardCharsets.UTF_8);
            return "\n```" + extension(path) + "\n" + content + "\n```\n";
        } catch (IOException e) {
            throw RpcException.business("PART_READ_FAILED", "failed to read file: " + e.getMessage(), e);
        }
    }

    private static AttachedContent binaryAttachment(JsonNode part, Path workspaceRoot, Path stagingRoot,
                                                    AttachedContent.AttachedContentType contentType) {
        var path = resolvePath(part, workspaceRoot, stagingRoot);
        try {
            long size = Files.size(path);
            if (size > MAX_BINARY_BYTES) {
                throw RpcException.business("PART_TOO_LARGE", "attachment is larger than 16MB");
            }
            var data = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            var fileName = path.getFileName();
            return AttachedContent.ofBase64(data, mediaType(path), contentType, fileName == null ? "" : fileName.toString());
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

    public record Parsed(String text, List<AttachedContent> attachments) { }
}
