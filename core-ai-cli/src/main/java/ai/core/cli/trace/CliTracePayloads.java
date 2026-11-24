package ai.core.cli.trace;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Collects the images a CLI trace still owns locally, so the upload can carry their bytes once instead
 * of leaving unresolvable refs on the server. Only files under the session media root are read; a
 * forged snapshot cannot turn a trace upload into an arbitrary file read.
 *
 * @author core-ai
 */
final class CliTracePayloads {
    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;
    private static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;

    static List<CliTraceRequest.CliTracePayload> collect(List<CliTraceSpan> spans, Path mediaRoot) {
        if (spans == null || mediaRoot == null) return List.of();
        var root = mediaRoot.toAbsolutePath().normalize();
        var payloads = new ArrayList<CliTraceRequest.CliTracePayload>();
        var seen = new HashSet<String>();
        long total = 0;
        for (var span : spans) {
            if (span == null || span.traceId == null || span.input == null || !span.input.contains("image_ref")) continue;
            for (var ref : refs(span.input)) {
                var dataUrl = dataUrl(ref, root, total);
                if (dataUrl == null || !seen.add(dataUrl)) continue;
                total += decodedBytes(dataUrl);
                if (total > MAX_TOTAL_BYTES) return payloads;
                var payload = new CliTraceRequest.CliTracePayload();
                payload.traceId = span.traceId;
                payload.dataUrl = dataUrl;
                payloads.add(payload);
            }
        }
        return payloads;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> refs(String input) {
        try {
            var messages = JsonUtil.fromJson(new TypeReference<List<Map<String, Object>>>() {
            }, input);
            var refs = new ArrayList<Map<String, Object>>();
            for (var message : messages) {
                if (!(message.get("content") instanceof List<?> parts)) continue;
                for (var part : parts) {
                    if (!(part instanceof Map<?, ?> partMap) || !"image_ref".equals(partMap.get("type"))) continue;
                    if (partMap.get("image_ref") instanceof Map<?, ?> ref) {
                        refs.add((Map<String, Object>) ref);
                    }
                }
            }
            return refs;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String dataUrl(Map<String, Object> ref, Path mediaRoot, long total) {
        var kind = (String) ref.get("kind");
        if (!"file".equals(kind) && !"sandbox".equals(kind)) return null;
        var path = (String) ref.get("path");
        if (path == null) return null;
        try {
            var file = Path.of(path).toAbsolutePath().normalize();
            if (!file.startsWith(mediaRoot) || !Files.isRegularFile(file)) return null;
            var size = Files.size(file);
            if (size == 0 || size > MAX_IMAGE_BYTES || total + size > MAX_TOTAL_BYTES) return null;
            var mediaType = (String) ref.get("media_type");
            return "data:" + (mediaType == null ? "image/png" : mediaType) + ";base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private static long decodedBytes(String dataUrl) {
        var comma = dataUrl.indexOf(',');
        return comma < 0 ? 0 : (dataUrl.length() - comma - 1L) / 4 * 3;
    }

    private CliTracePayloads() {
    }
}
