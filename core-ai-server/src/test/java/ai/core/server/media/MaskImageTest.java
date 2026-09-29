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
class MaskImageTest {
    private static BufferedImage maskImage(int width, int height, Rectangle selection) {
        var image = opaque(width, height);
        var graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(selection.x, selection.y, selection.width, selection.height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage opaque(int width, int height) {
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
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void rejectsMissingOversizedOrUndecodableMasks() {
        assertThrows(BadRequestException.class, () -> MaskImage.decode(null, 1024, 1024));
        assertThrows(BadRequestException.class, () -> MaskImage.decode("  ", 1024, 1024));
        // the encoded length is what has to fit the request body, so it is checked before decoding
        assertThrows(BadRequestException.class, () -> MaskImage.decode("A".repeat(8 * 1024 * 1024 + 1), 1024, 1024));
        assertThrows(BadRequestException.class, () -> MaskImage.decode("not base64 !!!", 1024, 1024));
        assertThrows(BadRequestException.class, () -> MaskImage.decode(base64("hello world".getBytes(StandardCharsets.UTF_8)), 1024, 1024));
    }

    @Test
    void rejectsMasksWithoutTransparencyOrWithoutAPaintedArea() {
        assertThrows(BadRequestException.class, () -> MaskImage.decode(base64(png(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB))), 64, 64));
        assertThrows(BadRequestException.class, () -> MaskImage.decode(base64(png(opaque(64, 64))), 64, 64));
    }

    @Test
    void rejectsAMaskWithDifferentProportionsThanTheImage() {
        var mask = maskImage(100, 100, new Rectangle(10, 10, 40, 40));

        assertThrows(BadRequestException.class, () -> MaskImage.decode(base64(png(mask)), 1000, 500));
    }

    @Test
    void keepsTheImageDimensionsWhenTheImageAlreadyFits() {
        var decoded = MaskImage.decode(base64(png(maskImage(1024, 1024, new Rectangle(100, 100, 50, 50)))), 1024, 1024);

        assertEquals(1024, decoded.width());
        assertEquals(1024, decoded.height());
        assertEquals(100, decoded.selectionX());
        assertEquals(100, decoded.selectionY());
        assertEquals(50, decoded.selectionWidth());
        assertEquals(50, decoded.selectionHeight());
        var png = AwtPngs.read(decoded.png());
        assertTrue((png.getRGB(120, 120) >>> 24) < 128, "painted pixels stay transparent");
        assertTrue((png.getRGB(10, 10) >>> 24) >= 128, "unpainted pixels stay opaque");
    }

    @Test
    void scalesBothSidesToTheWorkingSizeOfALargeImage() {
        var decoded = MaskImage.decode(base64(png(maskImage(1500, 1000, new Rectangle(0, 0, 300, 200)))), 6000, 4000);

        assertEquals(2048, decoded.width());
        assertEquals(1365, decoded.height());
        assertEquals(0, decoded.selectionX());
        assertEquals(0, decoded.selectionY());
        assertEquals(410, decoded.selectionWidth());
        assertEquals(273, decoded.selectionHeight());
    }

    @Test
    void snapsSizesToTheImageAspectRatio() {
        assertEquals("1024x1024", EditGeometry.snapSize(1024, 1024));
        assertEquals("1536x1024", EditGeometry.snapSize(3000, 2000));
        assertEquals("1024x1280", EditGeometry.snapSize(4000, 5000));
        assertEquals("1536x864", EditGeometry.snapSize(1920, 1080));
        assertEquals("1536x512", EditGeometry.snapSize(3000, 1000));
    }

    @Test
    void capsTheWorkingSizeAtTwoThousandAndFortyEight() {
        assertEquals(2048, EditGeometry.workingSize(6000, 4000)[0]);
        assertEquals(1365, EditGeometry.workingSize(6000, 4000)[1]);
        assertEquals(1024, EditGeometry.workingSize(1024, 768)[0]);
        assertEquals(2048, EditGeometry.workingSize(4000, 3000)[0]);
        assertEquals(1536, EditGeometry.workingSize(4000, 3000)[1]);
    }
}
