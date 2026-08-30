package ai.core.server.gateway;

import ai.core.media.MediaProvider;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import ai.core.server.rbac.PermissionsBypass;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.NotFoundException;

import java.nio.charset.StandardCharsets;

/**
 * Raw content endpoint for media jobs: stored image bytes for image jobs, upstream streaming for video jobs.
 *
 * @author Stephen
 */
@PermissionsBypass
public class MediaJobContentController {
    @Inject
    MediaJobService mediaJobService;
    @Inject
    FileService fileService;
    @Inject
    MediaProvider mediaProvider;

    public Response content(Request request) {
        var job = mediaJobService.get(request.pathParam("id"));
        if ("image".equals(job.mediaType)) return imageContent(job);
        return videoContent(job);
    }

    private Response imageContent(MediaJob job) {
        if (job.fileId == null) throw new NotFoundException("image content not stored, id=" + job.id);
        var record = fileService.get(job.fileId);
        return Response.bytes(fileService.getBytes(record))
                .contentType(record.contentType == null ? ContentType.APPLICATION_OCTET_STREAM : ContentType.create(record.contentType, StandardCharsets.UTF_8));
    }

    private Response videoContent(MediaJob job) {
        var bytes = mediaProvider.downloadVideo(GatewayVideoHandle.encode(job.id));
        var contentType = job.contentType == null ? "video/mp4" : job.contentType;
        return Response.bytes(bytes).contentType(ContentType.create(contentType, StandardCharsets.UTF_8));
    }
}
