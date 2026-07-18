package ai.core.server.gateway;

import core.framework.web.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * @author Stephen
 */
final class GatewayVideoHandle {
    private static final String PREFIX = "gateway-video-v1";

    private GatewayVideoHandle() {
    }

    static String encode(String jobId) {
        if (isBlank(jobId)) throw new BadRequestException("gateway media job ID is required");
        return PREFIX + "." + encodePart(jobId);
    }

    static String decode(String videoId) {
        if (isBlank(videoId)) throw new BadRequestException("video ID is required");
        var parts = videoId.split("\\.", -1);
        if (parts.length != 2 || !PREFIX.equals(parts[0])) {
            throw new BadRequestException("video ID was not created by this gateway");
        }
        try {
            var jobId = decodePart(parts[1]);
            if (isBlank(jobId)) throw new BadRequestException("invalid gateway video ID");
            return jobId;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid gateway video ID");
        }
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

}
