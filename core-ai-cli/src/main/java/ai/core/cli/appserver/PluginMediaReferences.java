package ai.core.cli.appserver;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Resolves page-supplied reference paths into provider-ready media references. Only files under the
 * workspace media root are readable; symlinks are resolved before the boundary check.
 *
 * @author stephen
 */
final class PluginMediaReferences {
    private static final long MAX_REFERENCE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_TOTAL_REFERENCE_BYTES = 24L * 1024 * 1024;
    private static final int MAX_REFERENCES = 4;

    static List<MediaReference> resolve(Path workspace, ObjectNode params, String field) {
        var node = params.get(field);
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw RpcException.invalidParams(field + " must be an array");
        if (node.size() > MAX_REFERENCES) throw RpcException.invalidParams(field + " accepts at most " + MAX_REFERENCES + " items");
        var result = new ArrayList<MediaReference>();
        var total = new long[1];
        var mediaRoot = workspace.resolve(".core-ai").resolve("media").normalize();
        Path realRoot;
        try {
            realRoot = mediaRoot.toRealPath();
        } catch (IOException e) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", "failed to read media root: " + e.getMessage(), e);
        }
        for (var item : node) {
            result.add(resolveOne(workspace, mediaRoot, realRoot, item, field, total));
        }
        return result;
    }

    private static MediaReference resolveOne(Path workspace, Path mediaRoot, Path realRoot, JsonNode item,
                                              String field, long[] total) {
        var relative = text(item, "path");
        if (relative == null) throw RpcException.invalidParams(field + " items need a media path");
        relative = relative.replace('\\', '/');
        var file = workspace.resolve(relative).normalize();
        if (!file.startsWith(mediaRoot) || !relative.startsWith(".core-ai/media/")) {
            throw RpcException.invalidParams("reference path must stay under .core-ai/media");
        }
        if (!Files.isRegularFile(file)) throw RpcException.invalidParams("reference file not found: " + relative);
        Path realFile;
        try {
            realFile = file.toRealPath();
        } catch (IOException e) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", "failed to read reference image: " + e.getMessage(), e);
        }
        if (!realFile.startsWith(realRoot)) {
            throw RpcException.invalidParams("reference path must stay under .core-ai/media");
        }
        long size;
        try {
            size = Files.size(realFile);
        } catch (IOException e) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", "failed to read reference image: " + e.getMessage(), e);
        }
        total[0] += size;
        if (size > MAX_REFERENCE_BYTES || total[0] > MAX_TOTAL_REFERENCE_BYTES) {
            throw RpcException.invalidParams("reference images are too large");
        }
        var contentType = contentType(realFile.getFileName().toString());
        if (contentType == null) throw RpcException.invalidParams("unsupported reference image type: " + relative);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(realFile);
        } catch (IOException e) {
            throw RpcException.business("MEDIA_GENERATION_FAILED", "failed to read reference image: " + e.getMessage(), e);
        }
        MediaReferenceRole role;
        try {
            role = MediaReferenceRole.parse(text(item, "role"));
        } catch (IllegalArgumentException e) {
            throw RpcException.invalidParams(e.getMessage(), e);
        }
        var dataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        return new MediaReference(null, dataUrl, null, text(item, "name"), role, MediaModality.IMAGE);
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static String contentType(String fileName) {
        var lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return null;
    }

    private PluginMediaReferences() {
    }
}
