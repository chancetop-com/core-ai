package ai.core.media.reference;

import java.util.Locale;

/**
 * What a reference contributes to the generation. Ordinal order is the trimming priority when a
 * model's reference limits are exceeded: lower ordinal = higher priority, trim from the tail.
 * Mirrors the role vocabulary of the drama render pipeline at the generic media layer.
 * <p>
 * {@link #FIRST_FRAME} / {@link #LAST_FRAME} are frame anchors: providers that expose a dedicated
 * first/last-frame parameter route them there instead of into the generic reference array, so the
 * video actually starts (ends) on that picture rather than merely resembling it.
 *
 * @author stephen
 */
public enum MediaReferenceRole {
    FIRST_FRAME, LAST_FRAME, SUBJECT, SCENE, CAMERA, STYLE, PROP, AUDIO;

    public static MediaReferenceRole parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown reference role: " + value
                    + " (expected one of first_frame, last_frame, subject, scene, camera, style, prop, audio)", e);
        }
    }

    public boolean isFrame() {
        return this == FIRST_FRAME || this == LAST_FRAME;
    }
}
