package ai.core.server.file;

import ai.core.server.util.ImageJpegCodec;

import java.util.Set;

/**
 * Builds the small jpeg tile that list surfaces show instead of the full-size original: a 2.5 MB
 * generated png becomes ~7 KB, so a 20 row gallery costs a fraction of a megabyte instead of ~50 MB.
 * Generation is best effort - anything unsupported, oversized or undecodable yields null and the
 * caller keeps serving the original.
 *
 * @author stephen
 */
public final class ImageThumbnailer {
    public static final int MAX_EDGE = 256;
    public static final long MAX_SOURCE_BYTES = 12L * 1024 * 1024;
    private static final float JPEG_QUALITY = 0.75f;
    // a full decode of a huge image is what would hurt the pod, so oversized dimensions are refused
    private static final long MAX_SOURCE_PIXELS = 16_000_000L;
    // an already small original is cheaper to serve as it is than as one more stored object
    private static final long ALREADY_SMALL_BYTES = 48 * 1024;
    private static final Set<String> SOURCE_TYPES = Set.of("image/png", "image/jpeg", "image/gif");

    public static boolean supports(String contentType) {
        return contentType != null && SOURCE_TYPES.contains(contentType);
    }

    /**
     * @return jpeg bytes within {@link #MAX_EDGE}, or null when the source is not worth thumbnailing:
     *         unsupported type, too large, undecodable, already small enough, or not compressible
     */
    public static byte[] thumbnail(byte[] source, String contentType) {
        if (source == null || source.length == 0 || source.length > MAX_SOURCE_BYTES) return null;
        if (!supports(contentType)) return null;
        if (source.length <= ALREADY_SMALL_BYTES) return null;
        var dimensions = ImageJpegCodec.dimensions(source);
        if (dimensions == null || (long) dimensions[0] * dimensions[1] > MAX_SOURCE_PIXELS) return null;
        var thumbnail = ImageJpegCodec.downscale(source, MAX_EDGE, JPEG_QUALITY);
        if (thumbnail == null || thumbnail.length >= source.length) return null;
        return thumbnail;
    }

    private ImageThumbnailer() {
    }
}
