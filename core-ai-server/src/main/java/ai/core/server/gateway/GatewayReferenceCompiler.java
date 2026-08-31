package ai.core.server.gateway;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaAddressingSyntax;
import ai.core.media.reference.MediaCapabilityRegistry;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaModelCapabilities;
import ai.core.media.reference.MediaPromptAddressing;
import ai.core.media.reference.MediaReferenceCompiler;
import ai.core.server.domain.GatewayModelConfig;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Binds the author-time reference names to the target model's positional tokens, and orders the
 * reference array to match.
 * <p>
 * The token/asset binding is positional — {@code @Image1} means {@code reference_image_urls[0]}, and
 * the arrays are the only binding the API documents. So the array builder and the token generator
 * must agree, or the model silently puts the wrong face on the wrong body, a failure with no error at
 * all. Compiling both from one ordered list is what guarantees they agree, including after a trim
 * renumbers everything.
 *
 * @author Stephen
 */
public class GatewayReferenceCompiler {
    /**
     * Code-level family defaults, overlaid with the admin-editable gateway_model row: adding a model
     * stays a registry row, never code. Public because the drama render path resolves the same facts
     * from the same row (design §9.1 v2.4) instead of keeping its own registry.
     */
    public static MediaModelCapabilities capabilities(String upstreamModel, GatewayModelConfig modelConfig) {
        var caps = MediaCapabilityRegistry.lookup(upstreamModel);
        if (modelConfig == null) return caps;
        return caps.merge(modelConfig.maxImageReferences, modelConfig.maxVideoReferences, modelConfig.maxAudioReferences,
                modelConfig.maxMixedReferences, MediaAddressingSyntax.parse(modelConfig.addressingSyntax), modelConfig.acceptsAudioReference);
    }

    private final MediaReferenceCompiler compiler = new MediaReferenceCompiler();

    public Compiled compile(String prompt, List<MediaReference> references, MediaModality defaultModality,
                            String upstreamModel, GatewayModelConfig modelConfig) {
        if (references == null || references.isEmpty()) return new Compiled(prompt, List.of(), List.of());
        var descriptor = new NamedReferenceDescriptor(names(references, defaultModality), defaultModality);
        var compiled = compiler.compile(references, descriptor, capabilities(upstreamModel, modelConfig));

        var nameToToken = new LinkedHashMap<String, String>();
        for (var token : compiled.references()) nameToToken.putIfAbsent(token.name(), token.token());
        var droppedNames = new LinkedHashSet<String>();
        var notes = new ArrayList<String>();
        for (var dropped : compiled.dropped()) {
            // never silently: "but I DID give a reference image" is the failure this reporting exists for
            if (!nameToToken.containsKey(dropped.name())) droppedNames.add(dropped.name());
            notes.add("reference \"" + dropped.name() + "\" was not sent: " + dropped.reason());
        }

        var rewritten = MediaPromptAddressing.rewrite(prompt, nameToToken, droppedNames);
        for (var unmentioned : rewritten.unmentionedNames()) {
            notes.add("reference \"" + unmentioned + "\" is not mentioned in the prompt; multi-reference models need one "
                    + "clearly scoped role sentence per reference, so state what it contributes.");
        }
        return new Compiled(rewritten.prompt(), compiled.accepted(), List.copyOf(notes));
    }

    /**
     * Position is the name when the caller declared none, which is what makes a raw {@code @image1} in
     * the prompt keep working: accepted, not documented.
     */
    private Map<MediaReference, String> names(List<MediaReference> references, MediaModality defaultModality) {
        // identity, not equality: two references with the same content are still two array slots
        var names = new IdentityHashMap<MediaReference, String>(references.size());
        var counters = new LinkedHashMap<MediaModality, Integer>();
        for (var reference : references) {
            var modality = reference.modality() == null ? defaultModality : reference.modality();
            var index = counters.merge(modality, 1, Integer::sum);
            var fallback = switch (modality) {
                case IMAGE -> "image" + index;
                case VIDEO -> "video" + index;
                case AUDIO -> "audio" + index;
            };
            names.put(reference, MediaPromptAddressing.isValidName(reference.name()) ? reference.name() : fallback);
        }
        return names;
    }

    private record NamedReferenceDescriptor(Map<MediaReference, String> names, MediaModality defaultModality)
            implements MediaReferenceCompiler.Descriptor<MediaReference> {
        @Override
        public MediaModality modality(MediaReference reference) {
            return reference.modality() == null ? defaultModality : reference.modality();
        }

        @Override
        public int rolePriority(MediaReference reference) {
            return reference.role() == null ? Integer.MAX_VALUE : reference.role().ordinal();
        }

        @Override
        public String label(MediaReference reference) {
            return names.get(reference);
        }
    }

    /**
     * @param references the accepted references, in the order the positional tokens bind to
     * @param notes non-fatal reporting handed back to the agent in the tool result
     */
    public record Compiled(String prompt, List<MediaReference> references, List<String> notes) {
    }
}
