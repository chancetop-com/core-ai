package ai.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

/**
 * @author stephen
 */
public final class ImageUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageUtil.class);
    public static final int DEFAULT_MAX_IMAGE_DIMENSION = 1920;
    public static final float DEFAULT_JPEG_QUALITY = 0.85f;

    public static CompressedImage compressImage(File file, byte[] originalBytes, String originalMimeType) throws IOException {
        return compressImage(file, originalBytes, originalMimeType, DEFAULT_MAX_IMAGE_DIMENSION, DEFAULT_JPEG_QUALITY);
    }

    public static CompressedImage compressImage(File file,
                                                byte[] originalBytes,
                                                String originalMimeType,
                                                int maxDimension,
                                                float jpegQuality) throws IOException {
        var image = ImageIO.read(file);
        if (image == null) {
            return new CompressedImage(originalBytes, originalMimeType);
        }

        var width = image.getWidth();
        var height = image.getHeight();

        if (width > maxDimension || height > maxDimension) {
            var scale = Math.min((double) maxDimension / width, (double) maxDimension / height);
            var newWidth = (int) (width * scale);
            var newHeight = (int) (height * scale);
            image = resizeImage(image, newWidth, newHeight);
            LOGGER.info("Resized image from {}x{} to {}x{}", width, height, newWidth, newHeight);
        }

        var outputStream = new ByteArrayOutputStream();
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return new CompressedImage(originalBytes, originalMimeType);
        }

        var writer = writers.next();
        try {
            var writeParam = writer.getDefaultWriteParam();
            writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            writeParam.setCompressionQuality(jpegQuality);

            var rgbImage = convertToRgb(image);

            var imageOutputStream = ImageIO.createImageOutputStream(outputStream);
            writer.setOutput(imageOutputStream);
            writer.write(null, new IIOImage(rgbImage, null, null), writeParam);
            imageOutputStream.close();
        } finally {
            writer.dispose();
        }

        return new CompressedImage(outputStream.toByteArray(), "image/jpeg");
    }

    public static BufferedImage resizeImage(BufferedImage original, int newWidth, int newHeight) {
        var type = original.getType() != 0 ? original.getType() : BufferedImage.TYPE_INT_RGB;
        var resized = new BufferedImage(newWidth, newHeight, type);
        var g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(original, 0, 0, newWidth, newHeight, null);
        g2d.dispose();
        return resized;
    }

    private static BufferedImage convertToRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB || image.getType() == BufferedImage.TYPE_4BYTE_ABGR) {
            var rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
            g.dispose();
            return rgbImage;
        }
        return image;
    }

    public record CompressedImage(byte[] data, String mimeType) { }
}
