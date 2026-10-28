package ai.core.server.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author stephen
 */
class ImageJpegCodecTest {
    @Test
    void keepsSmallImagesWhole() {
        assertEquals(1, ImageJpegCodec.subsampling(1000, 1000));
        assertEquals(1, ImageJpegCodec.subsampling(6000, 4000));
    }

    @Test
    void subsamplesOversizedPixels() {
        assertEquals(2, ImageJpegCodec.subsampling(8000, 8000));
        assertEquals(4, ImageJpegCodec.subsampling(16000, 16000));
    }

    @Test
    void capsSubsamplingForExtremeDimensions() {
        assertEquals(16, ImageJpegCodec.subsampling(100_000, 100_000));
    }
}
