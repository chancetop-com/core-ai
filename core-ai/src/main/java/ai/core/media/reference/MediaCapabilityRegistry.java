package ai.core.media.reference;

import java.util.List;

import static ai.core.media.reference.MediaAddressingSyntax.ANGLE_SUBJECT;
import static ai.core.media.reference.MediaAddressingSyntax.AT_TOKEN;
import static ai.core.media.reference.MediaAddressingSyntax.NONE;

/**
 * Code-level seed table of per-model reference capabilities, keyed by upstream model prefix exactly
 * as {@code MediaModelParameterHints} keys its hint text. The gateway overlays the admin-editable
 * {@code gateway_model} row on top of this, so adding a model stays a registry row; this table only
 * has to keep a brand-new upstream model from behaving worse than the family it belongs to.
 *
 * @author stephen
 */
public final class MediaCapabilityRegistry {
    // longest matching prefix wins, so ordering inside the list is irrelevant
    private static final List<Entry> ENTRIES = List.of(
            // Seedance 2.x addresses references as @Image1 / @Video1 / @Audio1 over a mixed reference set
            new Entry("bytedance/seedance-2", new MediaModelCapabilities("bytedance/seedance-2", 4, 2, 1, 4, AT_TOKEN, true)),
            new Entry("bytedance/seedance-1", new MediaModelCapabilities("bytedance/seedance-1", 2, 0, 0, 2, NONE, false)),
            // MiniMax H3 reference-to-video addresses as <Picture 1> / <Video 1>
            new Entry("minimax-h3/reference-to-video", new MediaModelCapabilities("minimax-h3/reference-to-video", 4, 2, 1, 4, ANGLE_SUBJECT, true)),
            new Entry("minimax-h3/image-to-video", new MediaModelCapabilities("minimax-h3/image-to-video", 2, 0, 0, 2, NONE, false)),
            new Entry("minimax-h3/text-to-video", new MediaModelCapabilities("minimax-h3/text-to-video", 0, 0, 0, 0, NONE, false)),
            new Entry("wan/2-7-r2v", new MediaModelCapabilities("wan/2-7-r2v", 5, 5, 1, 5, NONE, true)),
            new Entry("wan/2-7-image-to-video", new MediaModelCapabilities("wan/2-7-image-to-video", 2, 0, 1, 2, NONE, true)),
            new Entry("wan/2-7-text-to-video", new MediaModelCapabilities("wan/2-7-text-to-video", 0, 0, 0, 0, NONE, false)),
            new Entry("wan/", new MediaModelCapabilities("wan", 1, 0, 0, 1, NONE, false)),
            // Kling 3 "elements" are @element_name references in the prompt
            new Entry("kling-3.0/", new MediaModelCapabilities("kling-3.0", 2, 0, 0, 2, AT_TOKEN, false)),
            new Entry("kling/v3-", new MediaModelCapabilities("kling/v3", 2, 0, 0, 2, AT_TOKEN, false)),
            new Entry("kling-2.6/", new MediaModelCapabilities("kling-2.6", 1, 0, 0, 1, NONE, false)),
            new Entry("kling/v2-", new MediaModelCapabilities("kling/v2", 2, 0, 0, 2, NONE, false)),
            new Entry("grok-imagine-video-", new MediaModelCapabilities("grok-imagine-video", 7, 0, 0, 7, NONE, false)),
            new Entry("grok-imagine/text-to-video", new MediaModelCapabilities("grok-imagine/text-to-video", 0, 0, 0, 0, NONE, false)),
            new Entry("grok-imagine/", new MediaModelCapabilities("grok-imagine", 1, 0, 0, 1, NONE, false)),
            new Entry("hailuo/", new MediaModelCapabilities("hailuo", 1, 0, 0, 1, NONE, false)),
            new Entry("bytedance/v1-", new MediaModelCapabilities("bytedance/v1", 1, 0, 0, 1, NONE, false)),
            new Entry("pixverse/", new MediaModelCapabilities("pixverse", 1, 0, 0, 1, NONE, false)),
            new Entry("happyhorse", new MediaModelCapabilities("happyhorse", 1, 0, 0, 1, NONE, false)),
            // image families: no addressing syntax, so a stray @name must be rewritten away
            new Entry("seedream", new MediaModelCapabilities("seedream", 10, 0, 0, 10, NONE, false)),
            new Entry("nano-banana", new MediaModelCapabilities("nano-banana", 10, 0, 0, 10, NONE, false)),
            new Entry("flux", new MediaModelCapabilities("flux", 4, 0, 0, 4, NONE, false)),
            new Entry("gpt-image", new MediaModelCapabilities("gpt-image", 16, 0, 0, 16, NONE, false)),
            new Entry("dall-e", new MediaModelCapabilities("dall-e", 1, 0, 0, 1, NONE, false)),
            new Entry("imagen", new MediaModelCapabilities("imagen", 4, 0, 0, 4, NONE, false)),
            // gemini-omni 1.1: video references are a separate capability from continuing a video by
            // interaction id — 3 clips of up to 3s each. Audio references are documented as unsupported,
            // and the <IMAGE_REF_N> / <VIDEO_REF_N> tokens match no syntax here yet, so references ride
            // positionally with the tokens stripped.
            new Entry("gemini-omni", new MediaModelCapabilities("gemini-omni", 4, 3, 0, 4, NONE, false)),
            new Entry("gemini-", new MediaModelCapabilities("gemini", 8, 0, 0, 8, NONE, false)));

    public static MediaModelCapabilities lookup(String upstreamModel) {
        if (upstreamModel == null || upstreamModel.isBlank()) return MediaModelCapabilities.unconstrained("unknown");
        Entry best = null;
        for (var entry : ENTRIES) {
            if (upstreamModel.startsWith(entry.prefix()) && (best == null || entry.prefix().length() > best.prefix().length())) {
                best = entry;
            }
        }
        // an unknown model gets no trimming and no addressing rewrite, which is how references behaved
        // before this registry existed
        return best == null ? MediaModelCapabilities.unconstrained(upstreamModel) : best.caps();
    }

    private MediaCapabilityRegistry() {
    }

    private record Entry(String prefix, MediaModelCapabilities caps) {
    }
}
