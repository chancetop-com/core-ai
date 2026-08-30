package ai.core.media.reference;

/**
 * Per-model-family reference facts: how many references of each modality the model accepts, and how
 * the prompt addresses them. These are model-family properties, not provider properties — Seedance
 * and MiniMax both arrive via KIE yet address references differently.
 * <p>
 * Null limits mean "unconstrained"; the compiler only trims against limits it was actually given.
 *
 * @author stephen
 */
public record MediaModelCapabilities(String model,
                                     Integer maxImages,
                                     Integer maxVideos,
                                     Integer maxAudios,
                                     Integer maxMixedTotal,
                                     MediaAddressingSyntax addressingSyntax,
                                     boolean acceptsAudioRef) {

    public static MediaModelCapabilities unconstrained(String model) {
        return new MediaModelCapabilities(model, null, null, null, null, MediaAddressingSyntax.NONE, false);
    }

    public MediaAddressingSyntax addressingSyntaxOrNone() {
        return addressingSyntax == null ? MediaAddressingSyntax.NONE : addressingSyntax;
    }

    /** Overlays the non-null fields of an admin-maintained registry row onto a code-level default. */
    public MediaModelCapabilities merge(Integer images, Integer videos, Integer audios, Integer mixedTotal,
                                        MediaAddressingSyntax syntax, Boolean acceptsAudio) {
        return new MediaModelCapabilities(model,
                images == null ? maxImages : images,
                videos == null ? maxVideos : videos,
                audios == null ? maxAudios : audios,
                mixedTotal == null ? maxMixedTotal : mixedTotal,
                syntax == null ? addressingSyntax : syntax,
                acceptsAudio == null ? acceptsAudioRef : acceptsAudio);
    }
}
