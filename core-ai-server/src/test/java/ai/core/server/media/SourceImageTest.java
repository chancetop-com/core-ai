package ai.core.server.media;

import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SourceImageTest {
    private static BufferedImage maskImage(int width, int height, Rectangle selection) {
        var image = white(width, height);
        var graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(selection.x, selection.y, selection.width, selection.height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage white(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] png(BufferedImage image) {
        var output = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return output.toByteArray();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void rejectsImagesBeyondTheDecodeBudget() {
        var image = SourceImage.read(png(white(200, 100)), 200, 100);
        assertEquals(200, image.width());

        assertThrows(BadRequestException.class, () -> SourceImage.read(png(white(200, 100)), 6000, 3000));
        assertThrows(BadRequestException.class, () -> SourceImage.read("not an image".getBytes(StandardCharsets.UTF_8), 64, 64));
    }

    @Test
    void marksThePaintedRegionOnTheImageForModelsWithoutAMaskField() {
        var mask = MaskImage.decode(base64(png(maskImage(200, 200, new Rectangle(50, 50, 100, 100)))), 200, 200);
        var source = SourceImage.read(png(white(200, 200)), 200, 200);

        var marked = AwtPngs.read(source.annotated(mask));

        var untouched = marked.getRGB(10, 10);
        assertEquals(0xFFFFFFFF, untouched, "pixels outside the marking stay untouched");
        var inside = marked.getRGB(120, 120);
        assertTrue(inside != 0xFFFFFFFF, "the painted region is tinted");
        assertTrue(((inside >> 16) & 0xFF) > ((inside & 0xFF) + 40), "the tint is the marker colour");
        var border = marked.getRGB(50, 50);
        assertTrue(((border >> 16) & 0xFF) > 200 && (border & 0xFF) < 60, "the region is outlined");
    }
}
