package ai.core.server.media;

/**
 * Working resolution and upstream size of a region edit. The source and its mask always travel at the
 * same dimensions, capped so that neither the decode nor the re-encode can allocate an unbounded image:
 * a 6000x4000 photo would otherwise need 96 MB of ARGB pixels just to be handed to the model.
 *
 * @author stephen
 */
final class EditGeometry {
    static final int WORKING_MAX_EDGE = 2048;

    static int[] workingSize(int width, int height) {
        var ratio = Math.min(1.0, (double) WORKING_MAX_EDGE / Math.max(width, height));
        return new int[]{Math.max(1, (int) Math.round(width * ratio)), Math.max(1, (int) Math.round(height * ratio))};
    }

    /**
     * A size the edit model accepts at the source's own aspect ratio: short edge 1024, long edge up to
     * 1536, both multiples of 16 - the models resize their input to this anyway, and snapping to a fixed
     * pair of ratios would deform a 4:5 photo into 2:3 on the way in.
     */
    static String snapSize(int width, int height) {
        var ratio = (double) Math.max(width, height) / Math.max(1, Math.min(width, height));
        var longEdge = roundTo16((int) Math.round(1024 * Math.min(ratio, 1.5)));
        var shortEdge = Math.max(16, roundTo16((int) Math.round(longEdge / ratio)));
        return width >= height ? longEdge + "x" + shortEdge : shortEdge + "x" + longEdge;
    }

    private static int roundTo16(int value) {
        return Math.max(16, (value + 8) / 16 * 16);
    }

    private EditGeometry() {
    }
}
