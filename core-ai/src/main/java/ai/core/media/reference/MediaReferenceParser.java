package ai.core.media.reference;

import ai.core.media.domain.MediaReference;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * Parses the {@code input_images} / {@code input_references} tool argument into references. Pure
 * parsing, no I/O: the representation a reference ends up with is chosen by the gateway once the
 * destination provider is known.
 *
 * @author stephen
 */
public final class MediaReferenceParser {
    /** The literal accepted in place of a JSON array to mean "the images attached to this conversation". */
    public static final String ATTACHED = "attached";

    public static List<MediaReference> parse(String value, String argumentName) {
        return items(value, argumentName).stream().map(item -> parseItem(item, argumentName)).toList();
    }

    /**
     * The contract asks for a JSON array; a single reference the model left unwrapped is accepted anyway,
     * because a missing pair of brackets is unambiguous — there is exactly one item — and failing the whole
     * call over it teaches the model nothing it can act on.
     */
    private static List<Object> items(String value, String argumentName) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(arrayHelp(argumentName));
        var trimmed = value.trim();
        if (trimmed.startsWith("[")) {
            try {
                return JsonUtil.fromJson(new TypeReference<>() {
                }, trimmed);
            } catch (Exception e) {
                throw new IllegalArgumentException(arrayHelp(argumentName), e);
            }
        }
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> item = JsonUtil.fromJson(new TypeReference<>() {
                }, trimmed);
                return List.of(item);
            } catch (Exception e) {
                throw new IllegalArgumentException(arrayHelp(argumentName), e);
            }
        }
        // a bare item only when it is recognisably a reference: anything else is a broken argument, not a URL
        if (looksLikeReference(trimmed)) return List.of(trimmed);
        throw new IllegalArgumentException(arrayHelp(argumentName));
    }

    private static boolean looksLikeReference(String value) {
        return MediaReference.LAST.equalsIgnoreCase(value)
                || value.startsWith("data:")
                || value.startsWith("http://")
                || value.startsWith("https://")
                || value.startsWith("gateway-media-v1.")
                || value.startsWith("gateway-video-v1.")
                || value.startsWith("/");
    }

    private static String arrayHelp(String argumentName) {
        return argumentName + " must be a JSON array — square brackets are required even for a single reference, e.g. "
                + "[{\"media_id\":\"gateway-media-v1.img.abc\"}] or [\"last\"] or [{\"sandbox_path\":\"/tmp/fixed.jpg\"}] "
                + "or [{\"url\":\"https://...\"}] / [{\"b64Json\":\"data:image/png;base64,...\"}]";
    }

    public static MediaReference parseItem(Object item, String argumentName) {
        if (item instanceof String value) {
            var trimmed = value.trim();
            if (trimmed.startsWith("{")) {
                Map<String, Object> map = JsonUtil.fromJson(new TypeReference<>() { }, trimmed);
                return parseItem(map, argumentName);
            }
            if (MediaReference.LAST.equalsIgnoreCase(trimmed)) return MediaReference.ofMediaId(MediaReference.LAST, null, null);
            if (trimmed.startsWith("data:")) return new MediaReference(null, trimmed);
            // a bare gateway handle is a reference, not a URL
            if (trimmed.startsWith("gateway-media-v1.") || trimmed.startsWith("gateway-video-v1.")) {
                return MediaReference.ofMediaId(trimmed, null, null);
            }
            return new MediaReference(trimmed, null);
        }
        if (item instanceof Map<?, ?> map) {
            var mediaId = string(map, "media_id", "mediaId");
            var name = string(map, "name");
            var role = MediaReferenceRole.parse(string(map, "role"));
            var modality = modality(string(map, "modality"));
            var url = string(map, "url");
            var b64Json = string(map, "b64Json", "b64_json");
            if (mediaId == null && url == null && b64Json == null) {
                throw new IllegalArgumentException(argumentName + " item needs one of media_id, url or b64Json");
            }
            if (name != null && !MediaPromptAddressing.isValidName(name)) {
                throw new IllegalArgumentException(argumentName + " item name must be 1-64 characters of letters, digits, _ or -: " + name);
            }
            return new MediaReference(url, b64Json, mediaId, name, role, modality);
        }
        throw new IllegalArgumentException(argumentName + " item must be a reference object or a URL string");
    }

    private static MediaModality modality(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "image", "img" -> MediaModality.IMAGE;
            case "video", "vid" -> MediaModality.VIDEO;
            case "audio" -> MediaModality.AUDIO;
            default -> throw new IllegalArgumentException("unknown reference modality: " + value);
        };
    }

    private static String string(Map<?, ?> map, String... keys) {
        for (var key : keys) {
            if (map.get(key) instanceof String value && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private MediaReferenceParser() {
    }
}
