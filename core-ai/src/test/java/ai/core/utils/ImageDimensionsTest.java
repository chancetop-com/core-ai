package ai.core.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class ImageDimensionsTest {
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static byte[] pngHeader(int width, int height) {
        var bytes = new byte[24];
        System.arraycopy(PNG_MAGIC, 0, bytes, 0, PNG_MAGIC.length);
        bytes[12] = 'I';
        bytes[13] = 'H';
        bytes[14] = 'D';
        bytes[15] = 'R';
        bytes[16] = (byte) (width >> 24);
        bytes[17] = (byte) (width >> 16);
        bytes[18] = (byte) (width >> 8);
        bytes[19] = (byte) width;
        bytes[20] = (byte) (height >> 24);
        bytes[21] = (byte) (height >> 16);
        bytes[22] = (byte) (height >> 8);
        bytes[23] = (byte) height;
        return bytes;
    }

    private static byte[] jpegHeader(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xD8});
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE0, 0x00, 0x10});
        out.writeBytes(new byte[14]);
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xC0, 0x00, 0x11, 0x08});
        out.writeBytes(new byte[]{(byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width});
        return out.toByteArray();
    }

    private static byte[] gifHeader(int width, int height) {
        var bytes = new byte[10];
        bytes[0] = 'G';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = '8';
        bytes[4] = '9';
        bytes[5] = 'a';
        bytes[6] = (byte) width;
        bytes[7] = (byte) (width >> 8);
        bytes[8] = (byte) height;
        bytes[9] = (byte) (height >> 8);
        return bytes;
    }

    @Test
    void readsPngDimensions() {
        var size = ImageDimensions.of(pngHeader(2048, 1024), "png");

        assertNotNull(size);
        assertEquals(2048, size.width());
        assertEquals(1024, size.height());
    }

    @Test
    void readsJpegDimensions() {
        var size = ImageDimensions.of(jpegHeader(3024, 1964), "jpeg");

        assertNotNull(size);
        assertEquals(3024, size.width());
        assertEquals(1964, size.height());
    }

    @Test
    void readsGifDimensions() {
        var size = ImageDimensions.of(gifHeader(640, 480), "gif");

        assertNotNull(size);
        assertEquals(640, size.width());
        assertEquals(480, size.height());
    }

    @Test
    void returnsNullForUnknownFormatsAndUnreadablePayloads() {
        assertNull(ImageDimensions.of(new byte[24], "webp"));
        assertNull(ImageDimensions.of(null, "png"));
        assertNull(ImageDimensions.of(new byte[10], "png"));
    }

    @Test
    void readsDataUriAndBareBase64() {
        var encoded = Base64.getEncoder().encodeToString(pngHeader(512, 384));

        assertEquals("512x384", ImageDimensions.ofBase64("data:image/png;base64," + encoded).describe());
        assertEquals("512x384", ImageDimensions.ofBase64(encoded).describe());
    }

    @Test
    void returnsNullForTextAndEmptyPayloads() {
        assertNull(ImageDimensions.ofBase64(null));
        assertNull(ImageDimensions.ofBase64(""));
        assertNull(ImageDimensions.ofBase64("not-base64!!"));
        assertNull(ImageDimensions.ofBase64("data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[8])));
    }
}
