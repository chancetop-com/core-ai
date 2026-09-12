package ai.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Request-time size guard for inline base64 images: chat completions are stateless, so an image handed
 * to the model once is carried in the history and resent on every later turn, and a handful of
 * multi-megabyte keyframes push the request body past the upstream limit (DeepSeek answers HTTP 413
 * Request Entity Too Large).
 * Re-encoding needs javax.imageio/java.awt, whose AWT native methods (ColorModel/Raster/
 * BufferedImage.initIDs) a GraalVM native image cannot link, so the encoder is a host-provided
 * {@link Shrinker}; the JVM server registers one at startup, the native CLI has none and relies on
 * request-time pruning alone.
 * Shrinking never fails the caller: an undecodable or already small payload is passed through as is.
 *
 * @author stephen
 */
public final class ImageDownscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageDownscaler.class);
    public static final int MAX_EDGE = 1568;
    public static final long MAX_BYTES = 1024 * 1024;
    public static final float JPEG_QUALITY = 0.8f;
    private static final String JPEG_FORMAT = "image/jpeg";
    private static final AtomicReference<Shrinker> SHRINKER = new AtomicReference<>();

    /**
     * @param shrinker the host's jpeg re-encoder, null to disable shrinking (native images)
     */
    public static void register(Shrinker shrinker) {
        SHRINKER.set(shrinker);
    }

    /**
     * @return the original payload when no shrinker is registered, when the image is already small
     *         enough, or when the re-encoded payload is not smaller; an image/jpeg payload otherwise
     */
    public static InlineImage shrink(String base64, String format) {
        var original = new InlineImage(base64, format);
        var shrinker = SHRINKER.get();
        if (shrinker == null || base64 == null || base64.isEmpty()) return original;
        try {
            var shrunk = shrinker.shrinkToJpeg(base64);
            if (shrunk == null || shrunk.length() >= base64.length()) return original;
            return new InlineImage(shrunk, JPEG_FORMAT);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("failed to shrink inline image, sending it as is, error={}", e.getMessage());
            return original;
        }
    }

    private ImageDownscaler() {
    }

    /**
     * Host-provided jpeg re-encoder, implemented where javax.imageio and java.awt are usable.
     */
    public interface Shrinker {
        /**
         * @return the re-encoded jpeg payload, or null to keep the original image when it is already
         *         within {@link #MAX_EDGE} and {@link #MAX_BYTES}, or when it cannot be decoded
         */
        String shrinkToJpeg(String base64);
    }

    public record InlineImage(String data, String format) {
    }
}
