package ai.core.server.gateway;

import ai.core.media.reference.MediaModality;
import core.framework.web.exception.BadRequestException;

/**
 * Video-flavoured view of {@link GatewayMediaHandle}, kept as the entry point the video endpoints and
 * the {@code previous_video_id} argument already use.
 *
 * @author Stephen
 */
public final class GatewayVideoHandle {
    public static String encode(String jobId) {
        return GatewayMediaHandle.encodeVideo(jobId);
    }

    public static String decode(String videoId) {
        if (videoId == null || videoId.isBlank()) throw new BadRequestException("video ID is required");
        var handle = GatewayMediaHandle.decode(videoId);
        if (handle.modality() != MediaModality.VIDEO) {
            throw new BadRequestException("media ID is not a video: " + videoId);
        }
        return handle.jobId();
    }

    private GatewayVideoHandle() {
    }
}
