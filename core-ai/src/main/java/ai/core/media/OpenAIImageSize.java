package ai.core.media;

import java.util.Locale;

/**
 * The images API accepts an arbitrary frame size only when the width and the height are multiples of 16:
 * the short-drama line plans 1080x1920 keyframes and 1080 is not one, so the request bounces off the
 * provider ("Invalid size '1080x1920'. Width and height must both be divisible by 16") and a planned shot
 * never renders. Both dimensions are snapped onto that grid here, half up, so 1080 becomes 1088.
 *
 * @author Stephen
 */
final class OpenAIImageSize {
    /** every dimension the images API accepts is a multiple of this */
    private static final int DIMENSION_MULTIPLE = 16;

    /**
     * @return the size with both dimensions on the 16-pixel grid, or the size unchanged when it is not {@code WxH}
     */
    static String snap(String size) {
        if (size == null || size.isBlank()) return size;
        var parts = size.toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) return size;
        try {
            var width = snapDimension(Integer.parseInt(parts[0].trim()));
            var height = snapDimension(Integer.parseInt(parts[1].trim()));
            return width + "x" + height;
        } catch (NumberFormatException e) {
            return size;
        }
    }

    private static int snapDimension(int dimension) {
        var snapped = Math.round(dimension / (float) DIMENSION_MULTIPLE) * DIMENSION_MULTIPLE;
        return Math.max(snapped, DIMENSION_MULTIPLE);
    }

    private OpenAIImageSize() {
    }
}
