package ai.core.media.reference;

import ai.core.agent.ExecutionContext;
import ai.core.utils.ImageFormats;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rewrites {@code sandbox_path} media references into inline data, so a file the agent produced or fixed
 * inside its sandbox can be handed to a media model exactly like any other reference. This is the way back
 * for everything the platform cannot anticipate — a camera container the model rejects, a frame pulled out
 * of a video, a crop, a mask — without the agent having to mint a public URL first.
 *
 * @author stephen
 */
public final class SandboxMediaReferences {
    /** The item key accepted in place of url/b64Json: an absolute path inside the caller's own sandbox. */
    public static final String SANDBOX_PATH = "sandbox_path";

    /** Azure image edits accept a 50MB part; base64 costs a third more, so the bytes are capped well below it. */
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final List<String> ROOTS = List.of("/tmp/", "/workspace/");

    /**
     * @return the same JSON with every sandbox path read and inlined as {@code b64Json} — a single item or an
     *         array, matching the shape of the input; the input is returned untouched when it mentions no
     *         sandbox path
     */
    public static String expand(String value, String argumentName, ExecutionContext context) {
        if (value == null || value.isBlank() || !mentionsSandboxPath(value)) return value;
        return value.trim().startsWith("[") ? expandArray(value, argumentName, context) : expandOne(value, argumentName, context);
    }

    private static String expandArray(String value, String argumentName, ExecutionContext context) {
        List<Object> items;
        try {
            items = JsonUtil.fromJson(new TypeReference<>() {
            }, value);
        } catch (Exception e) {
            throw new IllegalArgumentException(argumentName + " must be a JSON array of references", e);
        }
        var rewritten = new ArrayList<Object>(items.size());
        for (var item : items) rewritten.add(expandItem(item, argumentName, context));
        return JsonUtil.toJson(rewritten);
    }

    private static String expandOne(String value, String argumentName, ExecutionContext context) {
        Map<String, Object> item;
        try {
            item = JsonUtil.fromJson(new TypeReference<>() {
            }, value);
        } catch (Exception e) {
            throw new IllegalArgumentException(argumentName + " must be a JSON object or a path", e);
        }
        return JsonUtil.toJson(expandItem(item, argumentName, context));
    }

    private static boolean mentionsSandboxPath(String value) {
        return value.contains(SANDBOX_PATH) || value.contains("sandboxPath")
                || value.contains("\"/tmp/") || value.contains("\"/workspace/");
    }

    private static Object expandItem(Object item, String argumentName, ExecutionContext context) {
        var path = sandboxPath(item);
        if (path == null) return item;
        var data = read(path, argumentName, context);
        var expanded = item instanceof Map<?, ?> map ? copyOf(map) : new LinkedHashMap<String, Object>();
        expanded.put("b64Json", data);
        return expanded;
    }

    private static String sandboxPath(Object item) {
        if (item instanceof String value) {
            var trimmed = value.trim();
            return trimmed.startsWith("/") ? trimmed : null;
        }
        if (item instanceof Map<?, ?> map) {
            for (var key : List.of(SANDBOX_PATH, "sandboxPath")) {
                if (map.get(key) instanceof String value && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    /** Keeps the keys the parser understands (name/role/modality) and drops the source the path replaces. */
    private static Map<String, Object> copyOf(Map<?, ?> map) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) continue;
            if (SANDBOX_PATH.equals(key) || "sandboxPath".equals(key)) continue;
            if ("url".equals(key) || "b64Json".equals(key) || "b64_json".equals(key)) continue;
            if ("media_id".equals(key) || "mediaId".equals(key)) continue;
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static String read(String path, String argumentName, ExecutionContext context) {
        var sandbox = context == null ? null : context.getSandbox();
        if (sandbox == null) {
            throw new IllegalArgumentException(argumentName + " item uses " + SANDBOX_PATH
                    + " but this session has no sandbox to read it from: " + path);
        }
        if (!ROOTS.stream().anyMatch(path::startsWith) || path.contains("..")) {
            throw new IllegalArgumentException(argumentName + " item " + SANDBOX_PATH
                    + " must be an absolute path under /tmp/ or /workspace/: " + path);
        }
        var file = sandbox.downloadFile(path);
        try {
            if (file.size() > MAX_BYTES) {
                throw new IllegalArgumentException(argumentName + " item " + SANDBOX_PATH + " is " + file.size()
                        + " bytes, over the " + MAX_BYTES / (1024 * 1024) + "MB limit; downscale or compress it first: " + path);
            }
            var bytes = Files.readAllBytes(file.path());
            return "data:" + mimeType(file.contentType(), bytes) + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException(argumentName + " item " + SANDBOX_PATH + " could not be read: " + path, e);
        } finally {
            deleteQuietly(file.path());
        }
    }

    private static String mimeType(String declared, byte[] bytes) {
        if (declared != null && !declared.isBlank() && !"application/octet-stream".equalsIgnoreCase(declared)) {
            return declared.toLowerCase(Locale.ROOT);
        }
        var format = ImageFormats.detect(bytes);
        return format != null ? ImageFormats.mimeType(format) : "application/octet-stream";
    }

    private static void deleteQuietly(java.nio.file.Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the temp file is reaped by the OS; a failure here must not fail the tool call
        }
    }

    private SandboxMediaReferences() {
    }
}
