package ai.core.image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * @author stephen
 */
public class ImageProcessUtils {
    public static ImageSize size(byte[] imageData) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(imageData));
            return new ImageSize(image.getWidth(), image.getHeight());
        } catch (IOException e) {
            throw new RuntimeException("Read image failed: " + e.getMessage(), e);
        }
    }

    public static byte[] resizeImage(byte[] imageData, int newWidth, int newHeight) {
        try {
            var originalImage = ImageIO.read(new ByteArrayInputStream(imageData));

            var at = new AffineTransform();
            at.scale((double) newWidth / originalImage.getWidth(), (double) newHeight / originalImage.getHeight());

            var hints = new RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            hints.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            AffineTransformOp op = new AffineTransformOp(at, hints);
            var resizedImage = op.filter(originalImage, null);

            var out = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Resize image failed: " + e.getMessage(), e);
        }
    }

    public static byte[] mergeImageMask(byte[] rawImageData, byte[] maskData, Color color) {
        try {
            var baseImage = ImageIO.read(new ByteArrayInputStream(rawImageData));
            var maskImage = ImageIO.read(new ByteArrayInputStream(maskData));

            if (baseImage.getWidth() != maskImage.getWidth() || baseImage.getHeight() != maskImage.getHeight()) {
                throw new IllegalArgumentException("The original image and the mask must have the same dimensions.");
            }

            var resultImage = new BufferedImage(baseImage.getWidth(), baseImage.getHeight(), BufferedImage.TYPE_INT_ARGB);

            for (int y = 0; y < baseImage.getHeight(); y++) {
                for (int x = 0; x < baseImage.getWidth(); x++) {
                    int maskColor = maskImage.getRGB(x, y);
                    boolean isWhite = (maskColor & 0x00FFFFFF) == 0x00FFFFFF;

                    if (isWhite) {
                        resultImage.setRGB(x, y, baseImage.getRGB(x, y));
                    } else {
                        resultImage.setRGB(x, y, color.getRGB());
                    }
                }
            }

            var out = new ByteArrayOutputStream();
            ImageIO.write(resultImage, "png", out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Error merging image and mask: " + e.getMessage(), e);
        }
    }
}
