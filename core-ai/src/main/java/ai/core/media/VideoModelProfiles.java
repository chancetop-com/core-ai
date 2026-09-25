package ai.core.media;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Video-model-family facts that decide whether a request is even valid upstream: the clip lengths
 * a family accepts, where a first/last frame goes (and whether frames exclude the reference array),
 * and the name of the native-audio switch. Keyed by upstream model prefix exactly like
 * {@link MediaModelParameterHints}, which documents the same facts for the agent in prose; this
 * class is the machine-readable half so callers can snap a duration or route a frame BEFORE the
 * request is rejected (a 3-second Seedance clip, a character sheet landing in Kling's first-frame
 * slot). Verified against docs.kie.ai model pages; unknown families get permissive defaults.
 *
 * @author stephen
 */
public final class VideoModelProfiles {
    private static final DurationPolicy ANY = new DurationPolicy(null, null, List.of());
    private static final Profile DEFAULT = new Profile("", ANY, null, null, null);

    // longest matching prefix wins
    private static final List<Profile> PROFILES = List.of(
            // seedance families accept a controlled multi-shot sequence written into one prompt (a stated HARD CUT inside
            // the clip) — sole reason the flag exists; the drama skill writes that card only for models that carry it.
            // They are ALSO frame-exclusive (docs.kie.ai: 图生视频-首帧 / 首尾帧 / 多模态参考 are 3 mutually exclusive
            // 场景) — but their docs give a way out: in the multimodal-reference scene a reference image may be named in
            // the prompt as the opening frame, so a request that needs identity references routes its frame into
            // reference_image_urls[0] instead of first_frame_url (frameViaReference), trading slot pinning for identity.
            new Profile("bytedance/seedance-2-5", DurationPolicy.range(4, 30), new FrameParams("first_frame_url", "last_frame_url", true), "generate_audio", null, true, true),
            new Profile("bytedance/seedance-2", DurationPolicy.range(4, 15), new FrameParams("first_frame_url", "last_frame_url", true), "generate_audio", null, true, true),
            // Doubao Seedance served straight from Volcano Ark: the same family facts as the bytedance/ names,
            // but a frame travels as a role-tagged content item (first_frame / last_frame), so the route names
            // are Ark's roles rather than the KIE market's input fields
            new Profile("doubao-seedance-2-5", DurationPolicy.range(4, 30), new FrameParams("first_frame", "last_frame", true), "generate_audio", null, true, true),
            new Profile("doubao-seedance-2", DurationPolicy.range(4, 15), new FrameParams("first_frame", "last_frame", true), "generate_audio", null, true, true),
            // 1.x: frames or reference images, never both, and their docs give no multimodal-reference way out
            new Profile("doubao-seedance-1-5", DurationPolicy.range(4, 12), new FrameParams("first_frame", "last_frame", true), "generate_audio", null),
            new Profile("doubao-seedance-1", DurationPolicy.range(2, 12), new FrameParams("first_frame", "last_frame", true), null, null),
            // seedance 1.5 input_urls is positional (first, last) — the array itself is the frame slot
            new Profile("bytedance/seedance-1", DurationPolicy.range(4, 12), new FrameParams(null, null, true), "generate_audio", null, true),
            new Profile("bytedance/v1-", DurationPolicy.fixed(5, 10), new FrameParams(null, null, true), null, null),
            new Profile("minimax-h3/reference-to-video", DurationPolicy.range(4, 15), null, null, null),
            new Profile("minimax-h3/image-to-video", DurationPolicy.range(4, 15), new FrameParams("first_frame_url", "last_frame_url", true), null, null),
            new Profile("minimax-h3/text-to-video", DurationPolicy.range(4, 15), null, null, null),
            new Profile("wan/2-7-image-to-video", DurationPolicy.range(2, 15), new FrameParams("first_frame_url", "last_frame_url", true), null, null),
            // r2v takes a single first_frame next to its reference_image array: frames and references coexist
            new Profile("wan/2-7-r2v", DurationPolicy.range(2, 10), new FrameParams("first_frame", null, false), null, null),
            new Profile("wan/2-7-", DurationPolicy.range(2, 15), null, null, null),
            new Profile("wan/", DurationPolicy.fixed(5, 10, 15), new FrameParams(null, null, true), null, null),
            // kling 3 image_urls is positional first/last (max 2) — a character sheet in slot 0 becomes the opening frame
            new Profile("kling-3.0/", DurationPolicy.range(3, 15), new FrameParams(null, null, true), "sound", null),
            new Profile("kling/v3-", DurationPolicy.range(3, 15), new FrameParams(null, null, true), "sound", null),
            new Profile("kling-2.6/", DurationPolicy.fixed(5, 10), new FrameParams(null, null, true), "sound", "negative_prompt"),
            new Profile("kling/v2-", DurationPolicy.fixed(5, 10), new FrameParams("image_url", "tail_image_url", true), null, "negative_prompt"),
            new Profile("grok-imagine-video-", DurationPolicy.range(1, 15), new FrameParams(null, null, false), null, null),
            new Profile("grok-imagine/text-to-video", DurationPolicy.range(1, 15), null, null, null),
            new Profile("grok-imagine/", DurationPolicy.range(6, 30), new FrameParams(null, null, true), null, null),
            new Profile("hailuo/", DurationPolicy.fixed(6, 10), new FrameParams(null, null, true), null, null),
            new Profile("pixverse/", DurationPolicy.range(1, 15), new FrameParams(null, null, true), null, null),
            new Profile("happyhorse", DurationPolicy.range(3, 15), new FrameParams(null, null, true), null, null),
            // gemini omni: first/last frame interpolation = the first two images of the input list, other references may follow;
            // 10s per turn (extendable by further turns), native audio always on
            new Profile("gemini-omni", new DurationPolicy(null, 10, List.of()), new FrameParams(null, null, false), null, null));

    public static Profile lookup(String upstreamModel) {
        if (upstreamModel == null || upstreamModel.isBlank()) return DEFAULT;
        Profile best = null;
        for (var profile : PROFILES) {
            if (upstreamModel.startsWith(profile.prefix()) && (best == null || profile.prefix().length() > best.prefix().length())) {
                best = profile;
            }
        }
        return best == null ? DEFAULT : best;
    }

    private VideoModelProfiles() {
    }

    /**
     * @param frames            null when the family has no notion of a frame anchor (references only)
     * @param audioParam        name of the native-audio boolean input, null when the family has none / is always on
     * @param negativePromptParam name of the negative-prompt input, null when the family ignores negatives
     * @param controlledMultiShot true when one generation can carry a stated HARD CUT (a multi-shot sequence in a
     *                            single clip) — false means the prompt must stay one continuous shot
     * @param frameViaReference true when this frame-exclusive family can still take references by sending the frame as a
     *                          reference image the prompt names as the opening (docs.kie.ai's multimodal-reference scene:
     *                          "可通过提示词指定参考图片作为首帧/尾帧"): the frame slot is given up to keep identity
     *                          references — a weaker pin on frame 0, chosen when identity matters more than the pin
     */
    public record Profile(String prefix, DurationPolicy durations, FrameParams frames, String audioParam, String negativePromptParam,
                          boolean controlledMultiShot, boolean frameViaReference) {
        public Profile(String prefix, DurationPolicy durations, FrameParams frames, String audioParam, String negativePromptParam,
                       boolean controlledMultiShot) {
            this(prefix, durations, frames, audioParam, negativePromptParam, controlledMultiShot, false);
        }

        public Profile(String prefix, DurationPolicy durations, FrameParams frames, String audioParam, String negativePromptParam) {
            this(prefix, durations, frames, audioParam, negativePromptParam, false, false);
        }

        /** True when a first/last frame must travel alone: the reference array would be rejected or would fill the frame slots. */
        public boolean frameExclusive() {
            return frames != null && frames.exclusive();
        }

        public boolean hasFrameSlots() {
            return frames != null;
        }
    }

    /**
     * Where a frame anchor is sent. Null field names mean "positional": the family's image array
     * IS the frame slot list (first frame at index 0, last frame at index 1), so frames must be
     * ordered ahead of any other image reference.
     *
     * @param exclusive whether frames and generic image references cannot be sent together
     */
    public record FrameParams(String firstField, String lastField, boolean exclusive) {
        public boolean positional() {
            return firstField == null;
        }
    }

    /**
     * Accepted clip lengths in whole seconds: either a contiguous range or an explicit list.
     * {@link #snap(int)} returns the closest accepted value not shorter than the request (or the
     * longest when the request exceeds them all) — a shot planned at 7s on a 5/10 model renders 10s
     * and gets trimmed at assembly, never rejected upstream.
     */
    public record DurationPolicy(Integer min, Integer max, List<Integer> choices) {
        public static DurationPolicy range(int min, int max) {
            return new DurationPolicy(min, max, List.of());
        }

        public static DurationPolicy fixed(Integer... values) {
            return new DurationPolicy(null, null, List.of(values));
        }

        public boolean unconstrained() {
            return min == null && max == null && choices.isEmpty();
        }

        public boolean accepts(int seconds) {
            if (!choices.isEmpty()) return choices.contains(seconds);
            if (min != null && seconds < min) return false;
            return max == null || seconds <= max;
        }

        public int snap(int seconds) {
            if (!choices.isEmpty()) {
                var sorted = choices.stream().sorted().toList();
                for (var choice : sorted) {
                    if (choice >= seconds) return choice;
                }
                return sorted.getLast();
            }
            if (min != null && seconds < min) return min;
            if (max != null && seconds > max) return max;
            return seconds;
        }

        public String describe() {
            if (!choices.isEmpty()) return choices.stream().sorted().map(String::valueOf).collect(Collectors.joining("/")) + "s";
            if (min == null && max == null) return "any";
            return (min == null ? "" : min) + "-" + (max == null ? "" : max) + "s";
        }

        @Override
        public String toString() {
            return describe().toLowerCase(Locale.ROOT);
        }
    }
}
