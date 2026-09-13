package ai.core.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class GeminiImageSizeTest {
    @Test
    void aspectRatioTakesTheClosestSupportedShape() {
        assertEquals("1:1", GeminiImageSize.aspectRatio("1024x1024"));
        assertEquals("9:16", GeminiImageSize.aspectRatio("1080x1920"));
        assertEquals("16:9", GeminiImageSize.aspectRatio("1280x720"));
        assertEquals("2:3", GeminiImageSize.aspectRatio("1024x1536"));
        assertEquals("3:2", GeminiImageSize.aspectRatio("1536x1024"));
        assertEquals("4:1", GeminiImageSize.aspectRatio("2000x500"));
    }

    @Test
    void imageSizeCoversTheRequestedPixelCount() {
        assertEquals("512", GeminiImageSize.imageSize("512x512"));
        assertEquals("512", GeminiImageSize.imageSize("384x384"));
        assertEquals("1K", GeminiImageSize.imageSize("1024x1024"));
        assertEquals("1K", GeminiImageSize.imageSize("1024x768"));
        assertEquals("2K", GeminiImageSize.imageSize("1024x1536"));
        assertEquals("2K", GeminiImageSize.imageSize("1080x1920"));
        assertEquals("2K", GeminiImageSize.imageSize("1536x2048"));
        assertEquals("2K", GeminiImageSize.imageSize("2048x2048"));
        assertEquals("4K", GeminiImageSize.imageSize("2500x2500"));
        assertEquals("4K", GeminiImageSize.imageSize("4096x4096"));
        assertEquals("4K", GeminiImageSize.imageSize("9000x9000"));
    }

    @Test
    void unparseableSizeIsLeftToTheModel() {
        assertNull(GeminiImageSize.aspectRatio(null));
        assertNull(GeminiImageSize.aspectRatio("wide"));
        assertNull(GeminiImageSize.aspectRatio("1024"));
        assertNull(GeminiImageSize.imageSize(null));
        assertNull(GeminiImageSize.imageSize("WxH"));
        assertNull(GeminiImageSize.imageSize("0x1024"));
    }
}
