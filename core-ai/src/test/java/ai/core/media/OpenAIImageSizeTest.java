package ai.core.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that a caller's planned canvas survives the images API size rule: every dimension is a multiple
 * of 16, and a vertical short-drama frame (1080x1920) is not.
 *
 * @author Stephen
 */
class OpenAIImageSizeTest {

    @Test
    void snapsEachDimensionOntoTheNearestSixteenPixelMultiple() {
        assertEquals("1088x1920", OpenAIImageSize.snap("1080x1920"));
        assertEquals("1088x1920", OpenAIImageSize.snap("1080X1920"));
        assertEquals("1088x1920", OpenAIImageSize.snap("1080 x 1920"));
        assertEquals("1088x1088", OpenAIImageSize.snap("1080x1080"));
        assertEquals("1008x1008", OpenAIImageSize.snap("1000x1000"));
        assertEquals("1008x1024", OpenAIImageSize.snap("1001x1023"));
    }

    @Test
    void neverSnapsADimensionBelowOnePixelBlock() {
        assertEquals("16x16", OpenAIImageSize.snap("4x4"));
        assertEquals("16x16", OpenAIImageSize.snap("1x1"));
    }

    @Test
    void leavesSizesThatAlreadyComplyUntouched() {
        assertEquals("1536x1024", OpenAIImageSize.snap("1536x1024"));
        assertEquals("2048x2048", OpenAIImageSize.snap("2048x2048"));
    }

    @Test
    void passesThroughAnythingThatIsNotAPixelSize() {
        assertEquals("9:16", OpenAIImageSize.snap("9:16"));
        assertEquals("auto", OpenAIImageSize.snap("auto"));
        assertEquals("1024", OpenAIImageSize.snap("1024"));
        assertEquals("", OpenAIImageSize.snap(""));
        assertNull(OpenAIImageSize.snap(null));
    }
}
