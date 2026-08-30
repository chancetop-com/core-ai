package ai.core.server.gateway;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.HttpRemoteMediaLoader;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.RemoteMediaLoader;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Turns symbolic references into the representation the <em>destination</em> provider can consume.
 * <p>
 * This is the structural fix the design exists for: the reference used to be a URL chosen at the tool
 * layer, but only the routing layer knows which provider will receive it. A URL that is perfect for
 * one destination (our own storage, for inlining) is unusable for another (KIE, which cannot reach
 * {@code localhost:8080}). Resolution therefore runs after routing, and picks the cheapest faithful
 * path available:
 * <ol>
 *   <li>provider-native continuation — same provider, conversational editing: no reference at all,
 *       just the upstream interaction id. Zero bytes moved, best fidelity.</li>
 *   <li>upstream-side asset reuse — same provider, its own result asset is still valid.</li>
 *   <li>pre-signed public URL — the provider fetches from the public internet and the source is in
 *       our object storage. Minted per call, never cached: the signature expires.</li>
 *   <li>inline base64 — the provider requires inline data, or the source is not in object storage.
 *       The only path before this design, demoted to last resort.</li>
 * </ol>
 *
 * @author Stephen
 */
public class MediaReferenceResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaReferenceResolver.class);

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private final MediaJobService mediaJobService;
    private final RemoteMediaLoader remoteMediaLoader;

    public MediaReferenceResolver(MediaJobService mediaJobService) {
        this(mediaJobService, new HttpRemoteMediaLoader());
    }

    MediaReferenceResolver(MediaJobService mediaJobService, RemoteMediaLoader remoteMediaLoader) {
        this.mediaJobService = mediaJobService;
        this.remoteMediaLoader = remoteMediaLoader;
    }

    public Resolved resolve(List<MediaReference> references, MediaModality defaultModality, GatewayRoute route,
                            MediaJobOwner owner, VideoBytesLoader videoBytesLoader) {
        if (references == null || references.isEmpty()) return new Resolved(List.of(), null);
        var capabilities = MediaProviderCapabilities.of(route.provider());
        var resolved = new ArrayList<MediaReference>(references.size());
        String interactionId = null;
        for (var reference : references) {
            if (!reference.isSymbolic()) {
                resolved.add(external(reference, defaultModality, capabilities));
                continue;
            }
            var job = job(reference, defaultModality, owner);
            var modality = modality(reference, job, defaultModality);
            // tier 1: the source came from this very provider and the provider keeps its own latents
            if (interactionId == null && capabilities.supportsInteractionChaining()
                    && route.provider().id.equals(job.providerId) && hasText(job.upstreamInteractionId)) {
                interactionId = job.upstreamInteractionId;
                continue;
            }
            resolved.add(materialize(reference, job, modality, route, capabilities, videoBytesLoader));
        }
        return new Resolved(List.copyOf(resolved), interactionId);
    }

    private MediaJob job(MediaReference reference, MediaModality defaultModality, MediaJobOwner owner) {
        var mediaId = reference.mediaId().trim();
        if (MediaReference.LAST.equalsIgnoreCase(mediaId)) {
            var modality = reference.modality() == null ? defaultModality : reference.modality();
            return mediaJobService.findLatestCompleted(owner, modality)
                    .orElseThrow(() -> new BadRequestException("media_id=\"last\" but this session has produced no completed "
                            + modality.name().toLowerCase(Locale.ROOT) + " yet; generate one first"));
        }
        return mediaJobService.resolveReference(mediaId, owner);
    }

    private MediaModality modality(MediaReference reference, MediaJob job, MediaModality defaultModality) {
        if (reference.modality() != null) return reference.modality();
        if ("image".equals(job.mediaType)) return MediaModality.IMAGE;
        if ("video".equals(job.mediaType)) return MediaModality.VIDEO;
        return defaultModality;
    }

    private MediaReference materialize(MediaReference reference, MediaJob job, MediaModality modality, GatewayRoute route,
                                       MediaProviderCapabilities capabilities, VideoBytesLoader videoBytesLoader) {
        var remote = capabilities.acceptsRemoteUrl();
        // tier 2: the producing provider is the destination and its own asset is still valid
        if (remote && upstreamAssetValid(job) && route.provider().id.equals(job.providerId)) {
            return reference.withContent(job.upstreamAssetUrl, null, modality);
        }
        var record = fileRecord(job, modality, videoBytesLoader);
        if (record == null) {
            // nothing of ours to hand over; the producer's asset is the only representation left
            if (remote && upstreamAssetValid(job)) return reference.withContent(job.upstreamAssetUrl, null, modality);
            throw new BadRequestException("media reference has no stored content, media_id=" + reference.mediaId());
        }
        // tier 3: the destination fetches from the public internet and we hold the bytes — hand it a
        // pre-signed URL it can actually reach and move zero bytes ourselves
        if (remote) {
            var url = mediaJobService.downloadUrl(record);
            if (hasText(url)) return reference.withContent(url, null, modality);
        }
        // tier 4: inline
        var bytes = mediaJobService.bytes(record);
        var contentType = hasText(record.contentType) ? record.contentType : defaultContentType(modality);
        return reference.withContent(null, "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes), modality);
    }

    private FileRecord fileRecord(MediaJob job, MediaModality modality, VideoBytesLoader videoBytesLoader) {
        var record = mediaJobService.fileRecord(job).orElse(null);
        if (record != null) return record;
        // video jobs never populate file_id until someone downloads them; do it once and cache
        if (modality != MediaModality.VIDEO || videoBytesLoader == null) return null;
        try {
            var bytes = videoBytesLoader.load(job);
            if (bytes == null || bytes.length == 0) return null;
            return mediaJobService.storeBytes(job, bytes, hasText(job.contentType) ? job.contentType : "video/mp4");
        } catch (RuntimeException e) {
            LOGGER.warn("failed to materialize video reference, job={}", job.id, e);
            return null;
        }
    }

    private boolean upstreamAssetValid(MediaJob job) {
        if (!hasText(job.upstreamAssetUrl)) return false;
        return job.upstreamAssetExpiresAt == null || job.upstreamAssetExpiresAt.isAfter(ZonedDateTime.now());
    }

    /**
     * Genuinely external content keeps the existing loader, including redirect following and the size
     * cap — but that path is now the exception rather than the norm, which shrinks the SSRF surface to
     * explicitly-external references.
     */
    private MediaReference external(MediaReference reference, MediaModality defaultModality, MediaProviderCapabilities capabilities) {
        var modality = reference.modality() == null ? defaultModality : reference.modality();
        if (hasText(reference.b64Json())) return reference.withModality(modality);
        if (!hasText(reference.url())) {
            throw new BadRequestException("reference requires media_id, url or b64Json");
        }
        if (capabilities.acceptsRemoteUrl()) return reference.withModality(modality);
        var loaded = remoteMediaLoader.load(reference.url());
        return reference.withContent(null,
                "data:" + loaded.contentTypeOr(defaultContentType(modality)) + ";base64,"
                        + Base64.getEncoder().encodeToString(loaded.data()), modality);
    }

    private String defaultContentType(MediaModality modality) {
        return switch (modality) {
            case VIDEO -> "video/mp4";
            case AUDIO -> "audio/mpeg";
            case IMAGE -> "image/png";
        };
    }

    /** Downloads an upstream video by its job, so a video reference can be materialised lazily. */
    public interface VideoBytesLoader {
        byte[] load(MediaJob job);
    }

    /**
     * @param interactionId set when tier 1 applied; the caller forwards it instead of a reference
     */
    public record Resolved(List<MediaReference> references, String interactionId) {
    }
}
