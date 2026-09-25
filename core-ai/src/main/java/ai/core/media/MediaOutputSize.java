package ai.core.media;

import java.util.List;
import java.util.Locale;

/**
 * Video models take the frame shape as an aspect-ratio enum and the pixel count as a separate resolution
 * tier, while callers speak the OpenAI {@code WxH} size. Deriving both from one place keeps them
 * consistent: sending the ratio alone left every request at the model's own default resolution.
 *
 * @author stephen
 */
final class MediaOutputSize {
    /** Landscape / portrait / square — the vocabulary every family in the KIE table accepts. */
    static String aspectRatio(String size) {
        var dimensions = dimensions(size);
        if (dimensions == null) return null;
        if (dimensions[0] == dimensions[1]) return "1:1";
        return dimensions[0] > dimensions[1] ? "16:9" : "9:16";
    }

    /**
     * Nearest of the ratios a family documents, by log distance — for families that take the whole
     * Seedance vocabulary (21:9 through 9:16), where the three-way split would silently render a
     * 4:5 request as 9:16.
     */
    static String nearestAspectRatio(String size, List<String> choices) {
        var ratio = ratio(size);
        if (ratio == null) return null;
        String nearest = null;
        var nearestDistance = Double.MAX_VALUE;
        for (var candidate : choices) {
            var distance = Math.abs(Math.log(ratio) - Math.log(ratio(candidate)));
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
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

    /** Accepts both the pixel size ({@code 1024x1536}) and a bare aspect ratio ({@code 3:2}). */
    private static Double ratio(String value) {
        if (value == null || value.isBlank()) return null;
        var parts = value.trim().toLowerCase(Locale.ROOT).split(value.indexOf(':') >= 0 ? ":" : "x");
        if (parts.length != 2) return null;
        try {
            var width = Double.parseDouble(parts[0].trim());
            var height = Double.parseDouble(parts[1].trim());
            return width <= 0 || height <= 0 ? null : width / height;
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private MediaOutputSize() {
    }
}
