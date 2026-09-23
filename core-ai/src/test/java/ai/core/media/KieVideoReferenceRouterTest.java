package ai.core.media;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where frames land in a KIE request. Frame slots and the reference array are mutually exclusive on the families whose
 * model page says so — but seedance's page also documents a way to keep both: the multimodal-reference scene lets the
 * prompt name a reference image as the opening frame, so a request that needs identity references sends its frame as
 * reference #1 instead of into the slot. Families without that documented route keep the hard refusal.
 *
 * @author stephen
 */
class KieVideoReferenceRouterTest {
    private static MediaReference image(String url, MediaReferenceRole role) {
        return new MediaReference(url, null, null, url.substring(url.lastIndexOf('/') + 1), role, MediaModality.IMAGE);
    }

    private static KieVideoReferenceRouter.Target target(String model, String referenceField, KieVideoReferenceRouter.Mode mode) {
        return new KieVideoReferenceRouter.Target(model, mode, referenceField, VideoModelProfiles.lookup(model));
    }

    @Test
    void seedanceTakesTheFrameAsReferenceOneWhenIdentityReferencesRideAlong() {
        var input = new LinkedHashMap<String, Object>();
        var references = List.of(image("http://x/opening.png", MediaReferenceRole.FIRST_FRAME),
            image("http://x/face.png", MediaReferenceRole.SUBJECT), image("http://x/room.png", MediaReferenceRole.SCENE));

        var sentFrame = KieVideoReferenceRouter.apply(input, target("bytedance/seedance-2-5", "reference_image_urls",
            KieVideoReferenceRouter.Mode.ARRAY), references, refs -> refs.stream().map(MediaReference::url).toList());

        assertTrue(sentFrame, "the frame travels as a reference on this family, and it is still a frame");
        assertEquals(List.of("http://x/opening.png", "http://x/face.png", "http://x/room.png"),
            input.get("reference_image_urls"), "the opening picture leads the array the prompt names it from");
        assertNull(input.get("first_frame_url"), "the slot is not used: it would exclude the very references the shot needs");
    }

    @Test
    void aFrameExclusiveFamilyWithoutThatRouteStillRefusesFramesAndReferencesTogether() {
        var input = new LinkedHashMap<String, Object>();
        var references = List.of(image("http://x/opening.png", MediaReferenceRole.FIRST_FRAME),
            image("http://x/face.png", MediaReferenceRole.SUBJECT));
        var minimax = target("minimax-h3/image-to-video", "first_frame_url", KieVideoReferenceRouter.Mode.FIRST_LAST);

        var refused = assertThrows(IllegalArgumentException.class,
            () -> KieVideoReferenceRouter.apply(input, minimax, references, refs -> refs.stream().map(MediaReference::url).toList()));

        assertTrue(refused.getMessage().contains("not both"), refused.getMessage());
    }

    @Test
    void aFrameAloneStillGoesIntoTheNamedSlot() {
        var input = new LinkedHashMap<String, Object>();
        var references = List.of(image("http://x/opening.png", MediaReferenceRole.FIRST_FRAME));

        KieVideoReferenceRouter.apply(input, target("bytedance/seedance-2-5", "reference_image_urls", KieVideoReferenceRouter.Mode.ARRAY),
            references, refs -> refs.stream().map(MediaReference::url).toList());

        assertEquals("http://x/opening.png", input.get("first_frame_url"), "no references: the slot is the strongest pin available");
        assertNull(input.get("reference_image_urls"));
    }

    @Test
    void videoAndAudioReferencesRideOnTheirOwnArrays() {
        var input = new LinkedHashMap<String, Object>();
        var references = List.of(image("http://x/opening.png", MediaReferenceRole.FIRST_FRAME),
            image("http://x/room.png", MediaReferenceRole.SCENE),
            new MediaReference("http://x/motion.mp4", null, null, "motion", MediaReferenceRole.CAMERA, MediaModality.VIDEO),
            new MediaReference("http://x/voice.mp3", null, null, "voice", MediaReferenceRole.AUDIO, MediaModality.AUDIO));

        KieVideoReferenceRouter.apply(input, target("bytedance/seedance-2-5", "reference_image_urls", KieVideoReferenceRouter.Mode.ARRAY),
            references, refs -> refs.stream().map(MediaReference::url).toList());

        assertEquals(List.of("http://x/motion.mp4"), input.get("reference_video_urls"));
        assertEquals(List.of("http://x/voice.mp3"), input.get("reference_audio_urls"));
        assertEquals(List.of("http://x/opening.png", "http://x/room.png"), input.get("reference_image_urls"));
    }
}
