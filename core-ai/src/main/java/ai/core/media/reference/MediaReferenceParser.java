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
        List<Object> items;
        try {
            items = JsonUtil.fromJson(new TypeReference<>() { }, value);
        } catch (Exception e) {
            throw new IllegalArgumentException(argumentName + " must be a JSON array of references: "
                    + "{\"media_id\":\"gateway-media-v1...\"} or \"last\" for an earlier generation, "
                    + "{\"url\":\"https://...\"} / {\"b64Json\":\"data:image/png;base64,...\"} for external content", e);
        }
        return items.stream().map(item -> parseItem(item, argumentName)).toList();
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
