package ai.core.media;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Places a video request's references into the KIE input of one model family. The reference arrays
 * are the only token/asset binding the KIE API documents, so the array order must stay exactly the
 * order the prompt tokens were compiled against — never reorder. Video and audio references ride on
 * their own arrays; putting them into the image array would silently hand the model the wrong kind
 * of asset.
 * <p>
 * Frame anchors ({@link MediaReferenceRole#FIRST_FRAME} / {@link MediaReferenceRole#LAST_FRAME}) are
 * routed to the family's frame parameters when it has named ones; on positional families they move
 * to the head of the image array, which is what the family reads as first/last frame.
 *
 * @author stephen
 */
final class KieVideoReferenceRouter {
    /**
     * @param urls resolves references to public URLs (uploading base64 payloads on the way)
     * @return whether a frame anchor was sent
     */
    static boolean apply(Map<String, Object> input, Target target, List<MediaReference> references, Function<List<MediaReference>, List<String>> urls) {
        var model = target.model();
        var mode = target.mode();
        var referenceField = target.referenceField();
        var profile = target.profile();
        var images = references.stream().filter(reference -> reference.modalityOrImage() == MediaModality.IMAGE).toList();
        var videoUrls = urls.apply(references.stream().filter(reference -> reference.modalityOrImage() == MediaModality.VIDEO).toList());
        var audioUrls = urls.apply(references.stream().filter(reference -> reference.modalityOrImage() == MediaModality.AUDIO).toList());
        if (!videoUrls.isEmpty()) input.put("reference_video_urls", videoUrls);
        if (!audioUrls.isEmpty()) input.put("reference_audio_urls", audioUrls);
        if (images.isEmpty()) return false;

        var first = images.stream().filter(reference -> reference.role() == MediaReferenceRole.FIRST_FRAME).findFirst().orElse(null);
        var last = images.stream().filter(reference -> reference.role() == MediaReferenceRole.LAST_FRAME).findFirst().orElse(null);
        var others = images.stream().filter(reference -> reference.role() == null || !reference.role().isFrame()).toList();
        var frames = profile.frames();
        if (frames != null && !frames.positional() && (first != null || last != null)) {
            if (frames.exclusive() && !others.isEmpty())
                throw new IllegalArgumentException(model + " takes either first/last frame images or reference images, not both: "
                        + "drop the " + others.size() + " reference image(s) or the frame");
            if (first != null) input.put(frames.firstField(), urls.apply(List.of(first)).getFirst());
            if (last != null && frames.lastField() != null) input.put(frames.lastField(), urls.apply(List.of(last)).getFirst());
            if (!others.isEmpty()) applyImageArray(input, model, mode, referenceField, urls.apply(others));
            return true;
        }
        // positional families (kling image_urls, seedance-1 input_urls, single image_url): frames lead the array
        var ordered = new ArrayList<MediaReference>(images.size());
        if (first != null) ordered.add(first);
        if (last != null) ordered.add(last);
        ordered.addAll(others);
        applyImageArray(input, model, mode, referenceField, urls.apply(ordered));
        return first != null || last != null;
    }

    private static void applyImageArray(Map<String, Object> input, String model, Mode mode, String referenceField, List<String> imageUrls) {
        switch (mode) {
            case ARRAY -> input.put(referenceField, imageUrls);
            case SINGLE -> {
                if (imageUrls.size() > 1)
                    throw new IllegalArgumentException(model + " accepts exactly one reference image, got " + imageUrls.size());
                input.put(referenceField, imageUrls.getFirst());
            }
            case FIRST_LAST -> {
                if (imageUrls.size() > 2)
                    throw new IllegalArgumentException(model + " accepts at most two reference images (first and last frame), got " + imageUrls.size());
                input.put("first_frame_url", imageUrls.getFirst());
                if (imageUrls.size() == 2) input.put("last_frame_url", imageUrls.get(1));
            }
            case NONE -> throw new IllegalArgumentException(model + " does not accept reference images");
            default -> throw new IllegalArgumentException("unexpected reference mode: " + mode);
        }
    }

    private KieVideoReferenceRouter() {
    }

    /** How a family's image references are sent (verified against docs.kie.ai model pages). */
    enum Mode { ARRAY, SINGLE, FIRST_LAST, NONE }

    /** The model family's reference contract: array shape + field name from the KIE table, frame/audio facts from the profile. */
    record Target(String model, Mode mode, String referenceField, VideoModelProfiles.Profile profile) {
    }
}
