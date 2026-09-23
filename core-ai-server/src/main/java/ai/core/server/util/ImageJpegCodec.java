package ai.core.server.util;

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

/**
 * javax.imageio/java.awt backed jpeg encoder shared by the server-side image paths (inline image
 * shrinking, list thumbnails). Only a JVM host can offer this: the AWT image pipeline calls native
 * methods that a GraalVM native image cannot link, which is why the encoder lives in the server
 * rather than in core-ai.
 *
 * @author stephen
 */
public final class ImageJpegCodec {

    /**
     * @return the re-encoded jpeg, downscaled to keep the longest edge within maxEdge, or null when
     *         the bytes are not a decodable image
     */
    public static byte[] downscale(byte[] source, int maxEdge, float quality) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) return null;
            return encode(scale(image, maxEdge), quality);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * @return {width, height} read from the image header without decoding, or null when the bytes are
     *         not a readable image container; callers use it to reject oversized images before paying
     *         for a full decode
     */
    public static int[] dimensions(byte[] source) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) return null;
            return readDimensions(input);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int[] readDimensions(ImageInputStream input) throws IOException {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) return null;
        var reader = readers.next();
        try {
            reader.setInput(input, true, true);
            return new int[]{reader.getWidth(0), reader.getHeight(0)};
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage scale(BufferedImage source, int maxEdge) {
        var width = source.getWidth();
        var height = source.getHeight();
        var ratio = Math.min(1.0, (double) maxEdge / Math.max(width, height));
        var targetWidth = Math.max(1, (int) Math.round(width * ratio));
        var targetHeight = Math.max(1, (int) Math.round(height * ratio));
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
        return target;
    }

    private static byte[] encode(BufferedImage image, float quality) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return null;
        var writer = writers.next();
        try {
            return writeJpeg(writer, image, quality);
        } finally {
            writer.dispose();
        }
    }

    private static byte[] writeJpeg(ImageWriter writer, BufferedImage image, float quality) throws IOException {
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        var output = new ByteArrayOutputStream();
        try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), param);
        }
        return output.toByteArray();
    }

    private ImageJpegCodec() {
    }
}
