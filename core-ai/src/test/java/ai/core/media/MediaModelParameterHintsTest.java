package ai.core.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class MediaModelParameterHintsTest {

    @Test
    void minimaxH3FamilyReturnsPerModeHints() {
        assertTrue(MediaModelParameterHints.videoHint("minimax-h3/text-to-video").contains("aspect_ratio REQUIRED"));
        assertTrue(MediaModelParameterHints.videoHint("minimax-h3/image-to-video").contains("first_frame_url"));
        assertTrue(MediaModelParameterHints.videoHint("minimax-h3/reference-to-video").contains("reference_image_urls"));
    }

    @Test
    void seedanceFamiliesReturnDistinctHints() {
        var seedance2 = MediaModelParameterHints.videoHint("bytedance/seedance-2-5");
        var seedance15 = MediaModelParameterHints.videoHint("bytedance/seedance-1.5-pro");

        assertTrue(seedance2.contains("reference_image_urls"));
        assertTrue(seedance2.contains("up to 30s"));
        assertTrue(seedance15.contains("input_urls"));
        assertTrue(seedance15.contains("duration 4-12s"));
    }

    @Test
    void wan27ModesReturnPerModeHints() {
        assertTrue(MediaModelParameterHints.videoHint("wan/2-7-image-to-video").contains("first_clip_url"));
        assertTrue(MediaModelParameterHints.videoHint("wan/2-7-r2v").contains("reference_image"));
        assertTrue(MediaModelParameterHints.videoHint("wan/2-7-text-to-video").contains("duration 2-15s"));
        assertTrue(MediaModelParameterHints.videoHint("wan/2-6-image-to-video").contains("\"5\"/\"10\"/\"15\""));
    }

    @Test
    void klingGenerationsReturnDistinctHints() {
        assertTrue(MediaModelParameterHints.videoHint("kling-2.6/image-to-video").contains("duration \"5\"/\"10\""));
        assertTrue(MediaModelParameterHints.videoHint("kling/v2-1-pro").contains("tail_image_url"));
        assertTrue(MediaModelParameterHints.videoHint("kling-3.0/video").contains("multi_shots"));
        assertTrue(MediaModelParameterHints.videoHint("kling/v3-turbo-image-to-video").contains("multi_shots"));
    }

    @Test
    void otherKieFamiliesReturnHints() {
        assertNotNull(MediaModelParameterHints.videoHint("hailuo/2-3-image-to-video-pro"));
        assertNotNull(MediaModelParameterHints.videoHint("grok-imagine/image-to-video"));
        assertNotNull(MediaModelParameterHints.videoHint("grok-imagine-video-1-5-preview"));
        assertNotNull(MediaModelParameterHints.videoHint("pixverse/v6-text-to-video"));
        assertNotNull(MediaModelParameterHints.videoHint("happyhorse/text-to-video"));
        assertNotNull(MediaModelParameterHints.videoHint("bytedance/v1-pro-image-to-video"));
        assertNotNull(MediaModelParameterHints.videoHint("gemini-omni"));
    }

    @Test
    void unknownModelsReturnNull() {
        assertNull(MediaModelParameterHints.videoHint("some/future-model"));
        assertNull(MediaModelParameterHints.videoHint(null));
    }

    @Test
    void imageHintsStillWork() {
        assertEquals(MediaModelParameterHints.videoHint("gpt-image-2"), null);
        assertNotNull(MediaModelParameterHints.imageHint("gpt-image-2"));
        assertNull(MediaModelParameterHints.imageHint("unknown"));
    }
}
