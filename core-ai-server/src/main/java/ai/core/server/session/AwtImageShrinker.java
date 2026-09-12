package ai.core.server.session;

import ai.core.utils.ImageDownscaler;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * javax.imageio/java.awt backed {@link ImageDownscaler.Shrinker}, registered by SessionModule.
 * Only a JVM host can offer this: the AWT image pipeline calls native methods that a GraalVM native
 * image cannot link, which is why the encoder lives in the server rather than in core-ai.
 *
 * @author stephen
 */
public class AwtImageShrinker implements ImageDownscaler.Shrinker {
    private static byte[] toJpeg(byte[] bytes) throws IOException {
        var source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) return null;
        var width = source.getWidth();
        var height = source.getHeight();
        var scale = Math.min(1.0, (double) ImageDownscaler.MAX_EDGE / Math.max(width, height));
        var targetWidth = Math.max(1, (int) Math.round(width * scale));
        var targetHeight = Math.max(1, (int) Math.round(height * scale));
        var target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // jpeg carries no alpha, transparent pixels would otherwise come out black
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return null;
        var writer = writers.next();
        try {
            return writeJpeg(writer, target);
        } finally {
            writer.dispose();
        }
    }

    private static byte[] writeJpeg(ImageWriter writer, BufferedImage image) throws IOException {
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(ImageDownscaler.JPEG_QUALITY);
        var output = new ByteArrayOutputStream();
        try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), param);
        }
        return output.toByteArray();
    }

    // header-only read, so an image that is already small enough never pays for a full decode
    private static int maxEdge(byte[] bytes) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return 0;
            return readMaxEdge(input);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int readMaxEdge(ImageInputStream input) throws IOException {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) return 0;
        var reader = readers.next();
        try {
            reader.setInput(input, true, true);
            return Math.max(reader.getWidth(0), reader.getHeight(0));
        } finally {
            reader.dispose();
        }
    }

    @Override
    public String shrinkToJpeg(String base64) {
        try {
            var bytes = Base64.getDecoder().decode(base64);
            if (bytes.length <= ImageDownscaler.MAX_BYTES && maxEdge(bytes) <= ImageDownscaler.MAX_EDGE) return null;
            var shrunk = toJpeg(bytes);
            return shrunk == null ? null : Base64.getEncoder().encodeToString(shrunk);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
