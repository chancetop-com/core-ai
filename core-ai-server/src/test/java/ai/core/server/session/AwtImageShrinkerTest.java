package ai.core.server.session;

import ai.core.utils.ImageDownscaler;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that oversized inline images are re-encoded down to a jpeg within the edge limit, that small
 * ones are left alone, and that any decoding failure keeps the original payload.
 *
 * @author stephen
 */
class AwtImageShrinkerTest {
    private final AwtImageShrinker shrinker = new AwtImageShrinker();

    @Test
    void largeImageIsShrunkToJpegWithinMaxEdge() throws Exception {
        var png = png(2000, 1000);

        var data = shrinker.shrinkToJpeg(Base64.getEncoder().encodeToString(png));

        var shrunk = Base64.getDecoder().decode(data);
        assertTrue(shrunk.length < png.length / 2, "expected a real reduction, was " + shrunk.length + " of " + png.length);
        var image = ImageIO.read(new ByteArrayInputStream(shrunk));
        assertEquals(ImageDownscaler.MAX_EDGE, Math.max(image.getWidth(), image.getHeight()));
    }

    @Test
    void smallImageIsKeptAsIs() throws Exception {
        var png = png(320, 200);

        assertNull(shrinker.shrinkToJpeg(Base64.getEncoder().encodeToString(png)));
    }

    @Test
    void oversizedJpegIsReencodedSmaller() throws Exception {
        var jpeg = jpeg(1600, 1000, 0.95f);

        var data = shrinker.shrinkToJpeg(Base64.getEncoder().encodeToString(jpeg));

        assertTrue(Base64.getDecoder().decode(data).length < jpeg.length);
    }

    @Test
    void invalidBase64IsKeptAsIs() {
        assertNull(shrinker.shrinkToJpeg("not base64 !!"));
    }

    @Test
    void payloadThatIsNotAnImageIsKeptAsIs() {
        var payload = Base64.getEncoder().encodeToString(new byte[2 * 1024 * 1024]);

        assertNull(shrinker.shrinkToJpeg(payload));
    }

    private byte[] png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(noise(width, height), "png", output);
        return output.toByteArray();
    }

    private byte[] jpeg(int width, int height, float quality) throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        var output = new ByteArrayOutputStream();
        try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(noise(width, height), null, null), param);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private BufferedImage noise(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }
}
