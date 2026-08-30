package ai.core.media.domain;

import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;

/**
 * One reference asset handed to a media generation call.
 * <p>
 * A reference is <em>symbolic</em> until the gateway resolves it: {@link #mediaId()} names an earlier
 * generation of this platform and carries no representation choice, because only the routing layer
 * knows which provider will receive it and what that provider can actually consume. {@link #url()} /
 * {@link #b64Json()} are the escape hatch for genuinely external content, and are also what a
 * resolved reference carries once the destination is known.
 * <p>
 * {@link #name()} is the author-time handle the prompt addresses ({@code @char_lin}); the gateway
 * compiles it into the target model's positional token. {@link #role()} drives trimming priority
 * when the model's reference limits are exceeded.
 *
 * @author stephen
 */
public record MediaReference(String url,
                             String b64Json,
                             String mediaId,
                             String name,
                             MediaReferenceRole role,
                             MediaModality modality) {

    /** {@code media_id} sentinel for the most recent completed media of this modality in this session. */
    public static final String LAST = "last";

    public MediaReference(String url, String b64Json) {
        this(url, b64Json, null, null, null, null);
    }

    public static MediaReference ofMediaId(String mediaId, String name, MediaReferenceRole role) {
        return new MediaReference(null, null, mediaId, name, role, null);
    }

    public MediaModality modalityOrImage() {
        return modality == null ? MediaModality.IMAGE : modality;
    }

    public boolean isSymbolic() {
        return mediaId != null && !mediaId.isBlank();
    }

    public boolean hasContent() {
        return url != null && !url.isBlank() || b64Json != null && !b64Json.isBlank();
    }

    /** Same reference, now carrying the representation the destination provider can consume. */
    public MediaReference withContent(String resolvedUrl, String resolvedB64Json, MediaModality resolvedModality) {
        return new MediaReference(resolvedUrl, resolvedB64Json, mediaId, name, role,
                resolvedModality == null ? modality : resolvedModality);
    }

    public MediaReference withModality(MediaModality resolvedModality) {
        return new MediaReference(url, b64Json, mediaId, name, role, resolvedModality);
    }
}
