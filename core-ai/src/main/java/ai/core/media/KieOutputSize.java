package ai.core.media;

import java.util.List;
import java.util.Locale;

/**
 * KIE video models take the frame shape as an {@code aspect_ratio} enum and the pixel count as a
 * separate {@code resolution} tier, while callers speak the OpenAI {@code WxH} size. Deriving both
 * from one place keeps them consistent: sending the ratio alone left every request at the model's
 * own default resolution.
 *
 * @author stephen
 */
final class KieOutputSize {
    static String aspectRatio(String size) {
        var dimensions = dimensions(size);
        if (dimensions == null) return null;
        if (dimensions[0] == dimensions[1]) return "1:1";
        return dimensions[0] > dimensions[1] ? "16:9" : "9:16";
    }

    /**
     * The largest tier the requested size covers, or the smallest tier when the size is below all of
     * them. Empty tiers mean the family was never verified against its model page — nothing is sent
     * and the model keeps its own default.
     */
    static String resolution(String size, List<Integer> tiers) {
        if (tiers.isEmpty()) return null;
        var dimensions = dimensions(size);
        if (dimensions == null) return null;
        var shortSide = Math.min(dimensions[0], dimensions[1]);
        var tier = tiers.getFirst();
        for (var candidate : tiers) {
            if (candidate <= shortSide && candidate > tier) tier = candidate;
        }
        return tier + "p";
    }

    /** {@code WxH} to {width, height}; null when the caller gave no parseable size. */
    private static int[] dimensions(String size) {
        if (size == null || size.isBlank()) return null;
        var parts = size.toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private KieOutputSize() {
    }
}
