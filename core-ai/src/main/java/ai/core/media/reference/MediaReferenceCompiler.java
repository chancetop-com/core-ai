package ai.core.media.reference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * Compiles a role-tagged reference set against a model capability descriptor: trims by role priority
 * when limits are exceeded, assigns per-modality positional tokens in the model's own syntax, and
 * reports every dropped reference explicitly — silent trimming causes the "but I DID give a
 * reference image" confusion. When the model does not accept audio references, all audio refs are
 * dropped and the TTS-driven fallback flag is raised.
 * <p>
 * The reference type is a parameter so both the generic media path ({@code MediaReference}) and the
 * drama render path ({@code DramaShotReference}) compile through the same implementation. Adding a
 * new model is one capability row, never code.
 *
 * @author stephen
 */
public class MediaReferenceCompiler {
    public <T> Compiled<T> compile(List<T> references, Descriptor<T> descriptor, MediaModelCapabilities caps) {
        if (references == null || references.isEmpty()) return new Compiled<>(List.of(), List.of(), false);
        var dropped = new ArrayList<Dropped<T>>();
        var accepted = new ArrayList<T>();
        var fallbackToTts = false;
        for (var reference : references) {
            if (descriptor.modality(reference) == MediaModality.AUDIO && !caps.acceptsAudioRef()) {
                dropped.add(new Dropped<>(reference, descriptor.label(reference),
                        "model " + caps.model() + " does not accept audio references; falling back to the TTS-driven route"));
                fallbackToTts = true;
            } else {
                accepted.add(reference);
            }
        }
        // stable sort: equal-priority references keep the caller's order, which is the array order the
        // positional tokens will bind to
        accepted.sort(Comparator.comparingInt(descriptor::rolePriority));
        var byModality = trimPerModality(accepted, descriptor, caps, dropped);
        var compiled = trimMixedTotal(byModality, descriptor, caps, dropped);
        return new Compiled<>(assignTokens(compiled, descriptor, caps), List.copyOf(dropped), fallbackToTts);
    }

    private <T> List<T> trimPerModality(List<T> references, Descriptor<T> descriptor, MediaModelCapabilities caps, List<Dropped<T>> dropped) {
        var counts = new EnumMap<MediaModality, Integer>(MediaModality.class);
        var kept = new ArrayList<T>();
        for (var reference : references) {
            var modality = descriptor.modality(reference);
            var limit = modalityLimit(modality, caps);
            var used = counts.getOrDefault(modality, 0);
            if (limit != null && used >= limit) {
                dropped.add(new Dropped<>(reference, descriptor.label(reference),
                        "over " + modality + " limit " + limit + " of model " + caps.model() + "; trimmed by role priority"));
            } else {
                counts.put(modality, used + 1);
                kept.add(reference);
            }
        }
        return kept;
    }

    private <T> List<T> trimMixedTotal(List<T> references, Descriptor<T> descriptor, MediaModelCapabilities caps, List<Dropped<T>> dropped) {
        if (caps.maxMixedTotal() == null || references.size() <= caps.maxMixedTotal()) return references;
        var kept = new ArrayList<>(references.subList(0, caps.maxMixedTotal()));
        for (var reference : references.subList(caps.maxMixedTotal(), references.size())) {
            dropped.add(new Dropped<>(reference, descriptor.label(reference),
                    "over mixed total " + caps.maxMixedTotal() + " of model " + caps.model() + "; trimmed by role priority"));
        }
        return kept;
    }

    private <T> List<Token<T>> assignTokens(List<T> references, Descriptor<T> descriptor, MediaModelCapabilities caps) {
        var counters = new EnumMap<MediaModality, Integer>(MediaModality.class);
        var compiled = new ArrayList<Token<T>>();
        for (var reference : references) {
            var modality = descriptor.modality(reference);
            var index = counters.merge(modality, 1, Integer::sum);
            compiled.add(new Token<>(reference, descriptor.label(reference), modality, index,
                    token(modality, index, caps.addressingSyntaxOrNone())));
        }
        return compiled;
    }

    private Integer modalityLimit(MediaModality modality, MediaModelCapabilities caps) {
        return switch (modality) {
            case IMAGE -> caps.maxImages();
            case VIDEO -> caps.maxVideos();
            case AUDIO -> caps.maxAudios();
        };
    }

    private String token(MediaModality modality, int index, MediaAddressingSyntax syntax) {
        if (syntax == MediaAddressingSyntax.ANGLE_SUBJECT) {
            var noun = switch (modality) {
                case IMAGE -> "Picture";
                case VIDEO -> "Video";
                case AUDIO -> "Audio";
            };
            return "<" + noun + " " + index + ">";
        }
        var noun = switch (modality) {
            case IMAGE -> "Image";
            case VIDEO -> "Video";
            case AUDIO -> "Audio";
        };
        return switch (syntax) {
            case BRACKET -> "[" + noun + index + "]";
            // no addressing syntax: the prompt gets a plain ordinal phrase instead of a stray token
            case NONE -> "reference " + noun.toLowerCase(Locale.ROOT) + " " + index;
            default -> "@" + noun + index;
        };
    }

    /** Adapts an arbitrary reference type to the two facts trimming needs. */
    public interface Descriptor<T> {
        MediaModality modality(T reference);

        /** Lower value is kept first when limits force a trim. */
        int rolePriority(T reference);

        /** Human-readable identity used in drop reports. */
        String label(T reference);
    }

    public record Token<T>(T reference, String name, MediaModality modality, int index, String token) {
    }

    public record Dropped<T>(T reference, String name, String reason) {
    }

    public record Compiled<T>(List<Token<T>> references, List<Dropped<T>> dropped, boolean fallbackToTts) {
        public List<T> accepted() {
            return references.stream().map(Token::reference).toList();
        }
    }
}
