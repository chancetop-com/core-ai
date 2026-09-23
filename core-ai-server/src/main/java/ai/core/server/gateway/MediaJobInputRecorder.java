package ai.core.server.gateway;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.HttpRemoteMediaLoader;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.RemoteMediaLoader;
import ai.core.server.domain.MediaJob;
import ai.core.server.domain.MediaJobInput;
import ai.core.server.file.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import static ai.core.server.gateway.GatewaySupport.hasText;

/**
 * Keeps the input side of a media generation: the reference assets, the inpaint mask and the video an edit
 * continued from, each anchored so it can still be opened long after the generation finished.
 * <p>
 * The three forms a reference arrives in are anchored differently on purpose. A {@code media_id} names an
 * earlier generation of this platform, so that job id is the anchor and zero bytes move. An external URL
 * is fetched once and stored, because the source URL is exactly the thing that expires (a KIE result URL
 * lives 24 hours). Inline base64 — an attachment or a sandbox file — is written to the same storage, since
 * the request is the only place those bytes ever existed.
 * <p>
 * Recording is best-effort in the strong sense: a reference that cannot be anchored is dropped with a
 * warning, never allowed to fail a generation that has already been produced and billed.
 *
 * @author stephen
 */
class MediaJobInputRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaJobInputRecorder.class);

    private final MediaJobService mediaJobService;
    private final RemoteMediaLoader remoteMediaLoader;

    MediaJobInputRecorder(MediaJobService mediaJobService) {
        this(mediaJobService, new HttpRemoteMediaLoader());
    }

    MediaJobInputRecorder(MediaJobService mediaJobService, RemoteMediaLoader remoteMediaLoader) {
        this.mediaJobService = mediaJobService;
        this.remoteMediaLoader = remoteMediaLoader;
    }

    /**
     * @param previousVideo the video a conversational edit continued from, already resolved by the caller
     */
    List<MediaJobInput> capture(MediaJobOwner owner, List<MediaReference> references, MediaReference mask, MediaJob previousVideo) {
        var inputs = new ArrayList<MediaJobInput>();
        if (previousVideo != null) inputs.add(fromJob(previousVideo, null, MediaJobInput.ROLE_PREVIOUS_VIDEO));
        if (references != null) {
            for (var reference : references) add(inputs, toInput(owner, reference, null));
        }
        add(inputs, toInput(owner, mask, MediaJobInput.ROLE_MASK));
        return List.copyOf(inputs);
    }

    private void add(List<MediaJobInput> inputs, MediaJobInput input) {
        if (input != null) inputs.add(input);
    }

    private MediaJobInput toInput(MediaJobOwner owner, MediaReference reference, String forcedRole) {
        if (reference == null) return null;
        try {
            if (reference.isSymbolic()) {
                var job = job(reference, owner);
                return job == null ? null : fromJob(job, reference.name(), role(reference, forcedRole));
            }
            var input = new MediaJobInput();
            input.name = reference.name();
            input.role = role(reference, forcedRole);
            input.modality = modality(reference).name().toLowerCase(Locale.ROOT);
            if (hasText(reference.b64Json())) {
                inline(input, reference, owner);
                return input;
            }
            if (hasText(reference.url())) {
                external(input, reference, owner);
                return input;
            }
            return null;
        } catch (RuntimeException e) {
            LOGGER.warn("media input capture failed, name={}, mediaId={}, url={}",
                    reference.name(), reference.mediaId(), reference.url(), e);
            return null;
        }
    }

    private MediaJobInput fromJob(MediaJob job, String name, String role) {
        var input = new MediaJobInput();
        input.kind = MediaJobInput.KIND_MEDIA;
        input.name = name;
        input.role = role;
        // legacy video rows predate media_type; a missing value means video
        input.modality = hasText(job.mediaType) ? job.mediaType : MediaModality.VIDEO.name().toLowerCase(Locale.ROOT);
        input.jobId = job.id;
        input.fileId = job.fileId;
        input.contentType = job.contentType;
        return input;
    }

    private MediaJob job(MediaReference reference, MediaJobOwner owner) {
        var mediaId = reference.mediaId().trim();
        if (MediaReference.LAST.equalsIgnoreCase(mediaId)) {
            return mediaJobService.findLatestCompleted(owner, modality(reference)).orElse(null);
        }
        return mediaJobService.get(GatewayMediaHandle.decode(mediaId).jobId());
    }

    private void inline(MediaJobInput input, MediaReference reference, MediaJobOwner owner) {
        var value = reference.b64Json();
        input.kind = MediaJobInput.KIND_INLINE;
        input.contentType = contentTypeOr(value, defaultContentType(input.modality));
        store(owner, payload(value), input);
    }

    private void external(MediaJobInput input, MediaReference reference, MediaJobOwner owner) {
        input.kind = MediaJobInput.KIND_URL;
        input.url = reference.url();
        var loaded = remoteMediaLoader.load(reference.url());
        input.contentType = loaded.contentTypeOr(defaultContentType(input.modality));
        store(owner, loaded.data(), input);
    }

    private void store(MediaJobOwner owner, byte[] bytes, MediaJobInput input) {
        try {
            var tempFile = Files.createTempFile("media-input-", FileService.extension(input.contentType));
            Files.write(tempFile, bytes);
            var fileName = hasText(input.name) && input.name.matches("[\\w.-]+") ? "reference-" + input.name : "reference";
            var record = mediaJobService.fileService.uploadIfAbsent(owner == null ? null : owner.userId(),
                    fileName + FileService.extension(input.contentType), input.contentType, tempFile);
            input.fileId = record.id;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store media input", e);
        }
    }

    private String role(MediaReference reference, String forcedRole) {
        if (forcedRole != null) return forcedRole;
        return reference.role() == null ? null : reference.role().name().toLowerCase(Locale.ROOT);
    }

    private MediaModality modality(MediaReference reference) {
        if (reference.modality() != null) return reference.modality();
        var mediaId = reference.mediaId() == null ? null : reference.mediaId().trim();
        // "last" carries no modality of its own; the resolver reads references as images for both tools
        if (mediaId != null && GatewayMediaHandle.isHandle(mediaId)) {
            return GatewayMediaHandle.decode(mediaId).modality();
        }
        return MediaModality.IMAGE;
    }

    private String defaultContentType(String modality) {
        return switch (modality) {
            case "video" -> "video/mp4";
            case "audio" -> "audio/mpeg";
            default -> "image/png";
        };
    }

    private String contentTypeOr(String dataUrl, String fallback) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return fallback;
        var separator = dataUrl.indexOf(',');
        if (separator <= 5) return fallback;
        var metadata = dataUrl.substring(5, separator);
        var semicolon = metadata.indexOf(';');
        var contentType = semicolon < 0 ? metadata : metadata.substring(0, semicolon);
        return contentType.isBlank() ? fallback : contentType;
    }

    private byte[] payload(String value) {
        var separator = value.indexOf(',');
        var encoded = separator >= 0 && value.startsWith("data:") ? value.substring(separator + 1) : value;
        return Base64.getDecoder().decode(encoded);
    }
}
