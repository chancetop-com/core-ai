package ai.core.server.session;

import ai.core.server.util.ImageJpegCodec;
import ai.core.utils.ImageDownscaler;

import java.util.Base64;

/**
 * javax.imageio/java.awt backed {@link ImageDownscaler.Shrinker}, registered by SessionModule.
 * Only a JVM host can offer this: the AWT image pipeline calls native methods that a GraalVM native
 * image cannot link, which is why the encoder lives in the server rather than in core-ai.
 *
 * @author stephen
 */
public class AwtImageShrinker implements ImageDownscaler.Shrinker {
    // header-only read, so an image that is already small enough never pays for a full decode
    private static int maxEdge(byte[] bytes) {
        var dimensions = ImageJpegCodec.dimensions(bytes);
        return dimensions == null ? 0 : Math.max(dimensions[0], dimensions[1]);
    }

    @Override
    public String shrinkToJpeg(String base64) {
        try {
            var bytes = Base64.getDecoder().decode(base64);
            if (bytes.length <= ImageDownscaler.MAX_BYTES && maxEdge(bytes) <= ImageDownscaler.MAX_EDGE) return null;
            var shrunk = ImageJpegCodec.downscale(bytes, ImageDownscaler.MAX_EDGE, ImageDownscaler.JPEG_QUALITY);
            return shrunk == null ? null : Base64.getEncoder().encodeToString(shrunk);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
