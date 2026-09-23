package ai.core.server.file;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the thumbnail policy: photos and generated images are re-encoded into a small jpeg, while
 * anything already small, unsupported, oversized or undecodable is left to the original.
 *
 * @author stephen
 */
class ImageThumbnailerTest {
    private static final Random NOISE = new Random(42);

    @Test
    void largeImageIsThumbnailedWithinMaxEdge() throws Exception {
        var png = png(1000, 600);

        var thumbnail = ImageThumbnailer.thumbnail(png, "image/png");

        assertNotNull(thumbnail);
        assertTrue(thumbnail.length < png.length / 10, "expected a real reduction, was " + thumbnail.length + " of " + png.length);
        var image = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertEquals(ImageThumbnailer.MAX_EDGE, Math.max(image.getWidth(), image.getHeight()));
        assertEquals(256, image.getWidth());
        assertEquals(154, image.getHeight());
    }

    @Test
    void smallImageIsLeftToTheOriginal() throws Exception {
        var png = png(64, 48);

        assertNull(ImageThumbnailer.thumbnail(png, "image/png"));
    }

    @Test
    void unsupportedContentTypeIsNotThumbnailed() throws Exception {
        var png = png(1000, 600);

        assertNull(ImageThumbnailer.thumbnail(png, "image/webp"));
        assertNull(ImageThumbnailer.thumbnail(png, null));
    }

    @Test
    void oversizedSourceIsNotThumbnailed() {
        var source = new byte[(int) ImageThumbnailer.MAX_SOURCE_BYTES + 1];

        assertNull(ImageThumbnailer.thumbnail(source, "image/png"));
    }

    @Test
    void payloadThatIsNotAnImageIsNotThumbnailed() {
        var payload = new byte[200 * 1024];

        assertNull(ImageThumbnailer.thumbnail(payload, "image/png"));
    }

    @Test
    void imageTooLargeToDecodeIsNotThumbnailed() {
        var headerOnly = pngHeader(8000, 8000, 200 * 1024);

        assertNull(ImageThumbnailer.thumbnail(headerOnly, "image/png"));
    }

    @Test
    void supportedTypesMatchTheDecodableFormats() {
        assertTrue(ImageThumbnailer.supports("image/png"));
        assertTrue(ImageThumbnailer.supports("image/jpeg"));
        assertTrue(ImageThumbnailer.supports("image/gif"));
        assertFalse(ImageThumbnailer.supports("image/svg+xml"));
        assertFalse(ImageThumbnailer.supports("video/mp4"));
    }

    private byte[] png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(noise(width, height), "png", output);
        return output.toByteArray();
    }

    private BufferedImage noise(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.setRGB(x, y, NOISE.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    // header plus a body nobody may decode: only the declared dimensions make it oversized
    private byte[] pngHeader(int width, int height, int bodyBytes) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        var header = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).array();
        writeChunk(output, "IHDR", header);
        writeChunk(output, "IDAT", new byte[bodyBytes]);
        return output.toByteArray();
    }

    private void writeChunk(ByteArrayOutputStream output, String type, byte[] data) {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        output.writeBytes(typeBytes);
        output.writeBytes(data);
        output.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
