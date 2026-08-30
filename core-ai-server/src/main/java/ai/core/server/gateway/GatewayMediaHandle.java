package ai.core.server.gateway;

import ai.core.media.reference.MediaModality;
import core.framework.web.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque, modality-aware handle for a generated media asset, backed by a {@code MediaJob} id:
 * <pre>
 * gateway-media-v1.img.&lt;base64url(jobId)&gt;
 * gateway-media-v1.vid.&lt;base64url(jobId)&gt;
 * </pre>
 * Images had no durable identifier before this — the only reference to a generated image lived in
 * the markdown of the conversation, so the agent had to copy a 120-character URL back through the
 * LLM to edit it. The legacy {@code gateway-video-v1.*} form keeps decoding: persisted video ids are
 * already in flight.
 *
 * @author Stephen
 */
public final class GatewayMediaHandle {
    private static final String PREFIX = "gateway-media-v1";
    private static final String LEGACY_VIDEO_PREFIX = "gateway-video-v1";
    private static final String IMAGE_TAG = "img";
    private static final String VIDEO_TAG = "vid";

    public static String encode(String jobId, MediaModality modality) {
        if (isBlank(jobId)) throw new BadRequestException("gateway media job ID is required");
        return PREFIX + "." + tag(modality) + "." + encodePart(jobId);
    }

    public static String encodeImage(String jobId) {
        return encode(jobId, MediaModality.IMAGE);
    }

    public static String encodeVideo(String jobId) {
        return encode(jobId, MediaModality.VIDEO);
    }

    public static boolean isHandle(String value) {
        if (isBlank(value)) return false;
        return value.startsWith(PREFIX + ".") || value.startsWith(LEGACY_VIDEO_PREFIX + ".");
    }

    public static Handle decode(String mediaId) {
        if (isBlank(mediaId)) throw new BadRequestException("media ID is required");
        var parts = mediaId.split("\\.", -1);
        if (parts.length == 2 && LEGACY_VIDEO_PREFIX.equals(parts[0])) {
            return new Handle(jobId(mediaId, parts[1]), MediaModality.VIDEO);
        }
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new BadRequestException("media ID was not created by this gateway: " + mediaId);
        }
        return new Handle(jobId(mediaId, parts[2]), modality(mediaId, parts[1]));
    }

    private static String jobId(String mediaId, String encoded) {
        try {
            var jobId = decodePart(encoded);
            if (isBlank(jobId)) throw new BadRequestException("invalid gateway media ID: " + mediaId);
            return jobId;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid gateway media ID: " + mediaId, "BAD_REQUEST", e);
        }
    }

    private static MediaModality modality(String mediaId, String tag) {
        return switch (tag) {
            case IMAGE_TAG -> MediaModality.IMAGE;
            case VIDEO_TAG -> MediaModality.VIDEO;
            default -> throw new BadRequestException("unknown media ID modality: " + mediaId);
        };
    }

    private static String tag(MediaModality modality) {
        return modality == MediaModality.VIDEO ? VIDEO_TAG : IMAGE_TAG;
    }

    private static String encodePart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private GatewayMediaHandle() {
    }

    public record Handle(String jobId, MediaModality modality) {
    }
}
