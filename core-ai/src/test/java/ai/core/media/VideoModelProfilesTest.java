package ai.core.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class VideoModelProfilesTest {
    @Test
    void durationsSnapToWhatTheFamilyAccepts() {
        assertEquals(4, VideoModelProfiles.lookup("bytedance/seedance-2-fast").durations().snap(3), "seedance rejects 3s");
        assertEquals(15, VideoModelProfiles.lookup("bytedance/seedance-2-fast").durations().snap(20));
        assertEquals(20, VideoModelProfiles.lookup("bytedance/seedance-2-5").durations().snap(20), "2.5 goes up to 30s");
        assertEquals(30, VideoModelProfiles.lookup("bytedance/seedance-2-5").durations().snap(35));
        assertEquals(10, VideoModelProfiles.lookup("kling-2.6/pro").durations().snap(7), "5/10 model: never shorter than planned");
        assertEquals(5, VideoModelProfiles.lookup("kling-2.6/pro").durations().snap(4));
        assertEquals(10, VideoModelProfiles.lookup("kling-2.6/pro").durations().snap(12), "longest when the plan exceeds them all");
        assertEquals(7, VideoModelProfiles.lookup("kling-3.0/std").durations().snap(7));
        assertEquals(6, VideoModelProfiles.lookup("hailuo/2-3-image-to-video-pro").durations().snap(5));
        assertEquals(7, VideoModelProfiles.lookup("some-unknown/model").durations().snap(7), "unknown families are not constrained");
        assertEquals("5/10s", VideoModelProfiles.lookup("kling-2.6/pro").durations().describe());
        assertEquals("4-15s", VideoModelProfiles.lookup("bytedance/seedance-2").durations().describe());
        assertTrue(VideoModelProfiles.lookup("kling-2.6/pro").durations().accepts(10));
        assertFalse(VideoModelProfiles.lookup("kling-2.6/pro").durations().accepts(7));
    }

    @Test
    void frameSlotsAndExclusivityFollowTheModelPages() {
        var seedance = VideoModelProfiles.lookup("bytedance/seedance-2-5");
        assertEquals("first_frame_url", seedance.frames().firstField());
        assertTrue(seedance.frameExclusive(), "frame mode and reference mode are mutually exclusive on seedance 2");
        assertEquals("generate_audio", seedance.audioParam());

        var kling3 = VideoModelProfiles.lookup("kling-3.0/std");
        assertTrue(kling3.frames().positional(), "image_urls[0] is the first frame");
        assertTrue(kling3.frameExclusive(), "two slots: a character sheet would become the opening frame");
        assertEquals("sound", kling3.audioParam());
        assertNull(kling3.negativePromptParam());

        var wanR2v = VideoModelProfiles.lookup("wan/2-7-r2v");
        assertEquals("first_frame", wanR2v.frames().firstField());
        assertFalse(wanR2v.frameExclusive(), "wan r2v takes a first frame next to its reference_image array");

        var h3 = VideoModelProfiles.lookup("minimax-h3/reference-to-video");
        assertNull(h3.frames(), "no frame slot at all: a first frame is just another reference");
        assertFalse(h3.frameExclusive());

        assertEquals("negative_prompt", VideoModelProfiles.lookup("kling-2.6/pro").negativePromptParam());

        var omni = VideoModelProfiles.lookup("gemini-omni-1.1-flash");
        assertTrue(omni.frames().positional(), "first/last frame are the first two images of the input list");
        assertFalse(omni.frameExclusive(), "other references may follow the frames");
        assertEquals(10, omni.durations().snap(12), "10s per turn");
    }
}
