package ai.core.media.reference;

import java.util.Locale;

/**
 * What a reference contributes to the generation. Ordinal order is the trimming priority when a
 * model's reference limits are exceeded: lower ordinal = higher priority, trim from the tail.
 * Mirrors the role vocabulary of the drama render pipeline at the generic media layer.
 *
 * @author stephen
 */
public enum MediaReferenceRole {
    SUBJECT, SCENE, CAMERA, STYLE, PROP, AUDIO;

    public static MediaReferenceRole parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown reference role: " + value
                    + " (expected one of subject, scene, camera, style, prop, audio)", e);
        }
    }
}
