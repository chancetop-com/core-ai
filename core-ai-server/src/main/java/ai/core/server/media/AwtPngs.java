package ai.core.server.media;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * javax.imageio/java.awt helpers shared by the region edit path. Kept in the server for the same reason
 * as {@code ImageJpegCodec}: the AWT pipeline cannot be linked into a GraalVM native image.
 *
 * @author stephen
 */
final class AwtPngs {
    static BufferedImage read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Scales into an ARGB buffer so a mask keeps its alpha and a photo loses nothing. */
    static BufferedImage scale(BufferedImage source, int width, int height) {
        var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    static byte[] png(BufferedImage image) {
        var output = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", output)) return null;
        } catch (IOException e) {
            return null;
        }
        return output.toByteArray();
    }

    private AwtPngs() {
    }
}
