package ai.core.media.reference;

import ai.core.agent.ExecutionContext;
import ai.core.utils.ImageFormats;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rewrites {@code sandbox_path} media references into inline data, so a file the agent produced or fixed
 * inside its sandbox can be handed to a media model exactly like any other reference. This is the way back
 * for everything the platform cannot anticipate — a camera container the model rejects, a frame pulled out
 * of a video, a crop, a mask — without the agent having to mint a public URL first.
 * <p>
 * A session without a sandbox (a server run with no provider, or the CLI) still works: the same field then
 * carries a path on the machine the agent itself works on, read directly instead of failing the call.
 *
 * @author stephen
 */
public final class SandboxMediaReferences {
    /** The item key accepted in place of url/b64Json: an absolute path in the caller's sandbox, or on this machine when it has none. */
    public static final String SANDBOX_PATH = "sandbox_path";

    /** Azure image edits accept a 50MB part; base64 costs a third more, so the bytes are capped well below it. */
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final List<String> ROOTS = List.of("/tmp/", "/workspace/");

    /**
     * Types the bytes cannot reveal on their own, for the no-sandbox read: they were cut straight from a
     * file the agent made. Images are sniffed before this map is consulted.
     */
    private static final Map<String, String> EXTENSION_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("m4v", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("webm", "video/webm"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("pdf", "application/pdf"));

    /**
     * @return the same JSON with every sandbox path read and inlined as {@code b64Json} — a single item or an
     *         array, matching the shape of the input; the input is returned untouched when it mentions no
     *         sandbox path
     */
    public static String expand(String value, String argumentName, ExecutionContext context) {
        if (value == null || value.isBlank() || !mentionsPath(value, context == null || context.getSandbox() == null)) return value;
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
        var trimmed = value.trim();
        if (!trimmed.startsWith("{")) {
            // a bare path: the model left the item unwrapped, which is unambiguous
            return JsonUtil.toJson(Map.of("b64Json", read(trimmed, argumentName, context)));
        }
        Map<String, Object> item;
        try {
            item = JsonUtil.fromJson(new TypeReference<>() {
            }, trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException(argumentName + " must be a JSON object or a path", e);
        }
        return JsonUtil.toJson(expandItem(item, argumentName, context));
    }

    /**
     * @param localFilesAllowed true when the session has no sandbox, so the field may name a path on this
     *                          machine — which is not restricted to the sandbox roots
     */
    private static boolean mentionsPath(String value, boolean localFilesAllowed) {
        if (value.contains(SANDBOX_PATH) || value.contains("sandboxPath")
                || value.contains("\"/tmp/") || value.contains("\"/workspace/")
                || value.startsWith("/tmp/") || value.startsWith("/workspace/")) {
            return true;
        }
        if (!localFilesAllowed) return false;
        return isAbsolutePath(value.trim());
    }

    private static Object expandItem(Object item, String argumentName, ExecutionContext context) {
        var path = sandboxPath(item, context == null || context.getSandbox() == null);
        if (path == null) return item;
        var data = read(path, argumentName, context);
        var expanded = item instanceof Map<?, ?> map ? copyOf(map) : new LinkedHashMap<String, Object>();
        expanded.put("b64Json", data);
        return expanded;
    }

    private static String sandboxPath(Object item, boolean localFilesAllowed) {
        if (item instanceof String value) {
            var trimmed = value.trim();
            if (trimmed.startsWith("/")) return trimmed;
            // without a sandbox a bare item is a path when it is an absolute one on this machine, as on the OS
            // the sandbox does not exist for: a Windows drive path is a path too
            return localFilesAllowed && isAbsolutePath(trimmed) ? trimmed : null;
        }
        if (item instanceof Map<?, ?> map) {
            for (var key : List.of(SANDBOX_PATH, "sandboxPath")) {
                if (map.get(key) instanceof String value && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    private static boolean isAbsolutePath(String value) {
        if (value.contains("://") || value.startsWith("data:")) return false;   // a url or an inline payload, never a path
        try {
            return Path.of(value).isAbsolute();
        } catch (RuntimeException e) {
            // the raw text is JSON or otherwise not a path — nothing to expand
            return false;
        }
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
        if (sandbox == null) return readLocalFile(path, argumentName);
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
            return "data:" + mimeType(file.contentType(), null, bytes) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException(argumentName + " item " + SANDBOX_PATH + " could not be read: " + path, e);
        } finally {
            deleteQuietly(file.path());
        }
    }

    /**
     * No sandbox on this session: the path names a file on the machine the agent itself works on — the server
     * host for a session, the user's machine for the CLI — where the agent already reads and writes through
     * run_bash_command, so naming a file there grants it nothing it does not have. A path that is not a
     * readable file is still a failure, but one the model can act on: it is told the file is not there and
     * which forms do work.
     */
    private static String readLocalFile(String path, String argumentName) {
        var file = localFile(path);
        if (file == null) {
            throw new IllegalArgumentException(argumentName + " item " + SANDBOX_PATH + " could not be read: this session has no sandbox, "
                    + "and " + path + " is not a readable file on this machine — pass the file's own absolute path, a url, or a media_id");
        }
        try {
            var size = Files.size(file);
            if (size > MAX_BYTES) {
                throw new IllegalArgumentException(argumentName + " item " + SANDBOX_PATH + " is " + size
                        + " bytes, over the " + MAX_BYTES / (1024 * 1024) + "MB limit; downscale or compress it first: " + path);
            }
            var bytes = Files.readAllBytes(file);
            return "data:" + mimeType(null, file, bytes) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException(argumentName + " item " + SANDBOX_PATH + " could not be read: " + path, e);
        }
    }

    private static Path localFile(String path) {
        try {
            var file = Path.of(path.trim());
            if (!file.isAbsolute() || !Files.isRegularFile(file) || !Files.isReadable(file)) return null;
            return file;
        } catch (RuntimeException e) {
            // an invalid path is as unreadable as a missing one, and the caller reports it the same way
            return null;
        }
    }

    private static String mimeType(String declared, Path file, byte[] bytes) {
        if (declared != null && !declared.isBlank() && !"application/octet-stream".equalsIgnoreCase(declared)) {
            return declared.toLowerCase(Locale.ROOT);
        }
        var format = ImageFormats.detect(bytes);
        if (format != null) return ImageFormats.mimeType(format);
        var extension = extensionOf(file);
        return extension == null ? "application/octet-stream" : EXTENSION_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static String extensionOf(Path file) {
        var name = file == null ? null : file.getFileName();
        if (name == null) return null;   // a root path carries no name, and no type either
        var text = name.toString();
        var dot = text.lastIndexOf('.');
        return dot < 0 || dot == text.length() - 1 ? null : text.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // the temp file is reaped by the OS; a failure here must not fail the tool call
        }
    }

    private SandboxMediaReferences() {
    }
}
