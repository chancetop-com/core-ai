package ai.core.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an image is recognized by its content and not by the name it arrived under, which is what
 * keeps a non-image payload (an error body saved as .png) away from the model APIs.
 *
 * @author Stephen
 */
class ImageFormatsTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void detectsFormatsBySignature() {
        assertEquals("png", ImageFormats.detect(PNG));
        assertEquals("jpeg", ImageFormats.detect(JPEG));
        assertEquals("gif", ImageFormats.detect(bytes("GIF89a")));
        assertEquals("webp", ImageFormats.detect(bytes("RIFFxxxxWEBPVP8 ")));
        assertEquals("bmp", ImageFormats.detect(new byte[]{'B', 'M', 0x36, 0x00}));
        assertEquals("tiff", ImageFormats.detect(new byte[]{'I', 'I', 0x2A, 0x00}));
    }

    @Test
    void onlySignaturesLongEnoughToBeCertainMatch() {
        assertNull(ImageFormats.detect(bytes("GIF8")));
        assertNull(ImageFormats.detect(bytes("RIFFxxxxNOPE")));
        assertNull(ImageFormats.detect(new byte[]{'I', 'I', 0x2A}));
    }

    @Test
    void aJsonErrorBodySavedAsPngIsNotAnImage() {
        var payload = "{\"errorCode\":\"NOT_FOUND\",\"message\":\"shared file not found\"}";

        assertNull(ImageFormats.detect(bytes(payload)));
        assertNull(ImageFormats.detectBase64(Base64.getEncoder().encodeToString(bytes(payload))));
    }

    @Test
    void detectsBase64WithoutDecodingTheWholePayload() {
        assertEquals("png", ImageFormats.detectBase64(Base64.getEncoder().encodeToString(PNG)));
        assertEquals("png", ImageFormats.detectBase64("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG)));
        assertNull(ImageFormats.detectBase64(""));
        assertNull(ImageFormats.detectBase64(null));
        assertNull(ImageFormats.detectBase64("not base64 ###"));
    }

    @Test
    void detectsBase64ThatIsLineWrapped() {
        var wrapped = Base64.getMimeEncoder(8, new byte[]{'\n'}).encodeToString(PNG);

        assertEquals("png", ImageFormats.detectBase64(wrapped));
    }

    @Test
    void modelReadsOnlyTheFormatsTheApisDecode() {
        assertTrue(ImageFormats.isModelReadable("png"));
        assertTrue(ImageFormats.isModelReadable("JPEG"));
        assertTrue(ImageFormats.isModelReadable("webp"));
        assertTrue(ImageFormats.isModelReadable("gif"));
        assertFalse(ImageFormats.isModelReadable("bmp"));
        assertFalse(ImageFormats.isModelReadable("tiff"));
        assertFalse(ImageFormats.isModelReadable(null));
    }

    @Test
    void mimeTypeMatchesTheFormat() {
        assertEquals("image/png", ImageFormats.mimeType("png"));
        assertEquals("image/jpeg", ImageFormats.mimeType("jpeg"));
    }
}
