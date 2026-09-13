package ai.core.media;

import java.util.List;
import java.util.Locale;

/**
 * Gemini image models take the frame shape as an {@code imageConfig.aspectRatio} enum and the pixel count
 * as a fixed {@code imageConfig.imageSize} tier (512 / 1K / 2K / 4K), while callers speak the OpenAI
 * {@code WxH} size. Both are derived here: without them the model answers in its own default 16:9 shape.
 *
 * @author stephen
 */
final class GeminiImageSize {
    /** aspect ratios accepted by the Gemini image family; the one closest to the requested shape wins */
    private static final List<String> ASPECT_RATIOS = List.of(
            "1:1", "1:4", "1:8", "2:3", "3:2", "3:4", "4:1", "4:3", "4:5", "5:4", "8:1", "9:16", "9:21", "16:9", "21:9");

    /** image tiers, smallest first, and the pixel count each covers whatever the frame shape is */
    private static final List<Tier> TIERS = List.of(
            new Tier("512", 262144L), new Tier("1K", 1048576L), new Tier("2K", 4194304L), new Tier("4K", 16777216L));

    static String aspectRatio(String size) {
        var dimensions = dimensions(size);
        if (dimensions == null) return null;
        var target = Math.log((double) dimensions[0] / dimensions[1]);
        var closest = ASPECT_RATIOS.getFirst();
        var closestDistance = Double.MAX_VALUE;
        for (var ratio : ASPECT_RATIOS) {
            var distance = Math.abs(target - Math.log(ratioValue(ratio)));
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = ratio;
            }
        }
        return closest;
    }

    /**
     * The smallest tier that still covers the requested pixel count: asking for 1080x1920 must not be answered
     * with a 768x1376 render the caller would have to upscale. Requests beyond 4K clamp to 4K.
     */
    static String imageSize(String size) {
        var dimensions = dimensions(size);
        if (dimensions == null || dimensions[0] <= 0 || dimensions[1] <= 0) return null;
        var requested = (long) dimensions[0] * dimensions[1];
        for (var tier : TIERS) {
            if (tier.pixels() >= requested) return tier.name();
        }
        return TIERS.getLast().name();
    }

    private static double ratioValue(String ratio) {
        var parts = ratio.split(":");
        return (double) Integer.parseInt(parts[0]) / Integer.parseInt(parts[1]);
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

    private GeminiImageSize() {
    }

    /** a resolution tier and the pixel count it covers, whatever the frame shape */
    private record Tier(String name, long pixels) {
    }
}
