package ai.core.server.gateway;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.MediaJob;
import ai.core.server.file.FileService;
import ai.core.server.rbac.PermissionsBypass;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.http.HTTPHeaders;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.NotFoundException;

import java.nio.charset.StandardCharsets;

/**
 * Raw content endpoint for media jobs: stored image bytes for image jobs, archived video bytes or a
 * pre-signed redirect for video jobs. Videos are materialized on first access if they are still alive
 * upstream; an expired upstream asset answers 410 instead of a 500.
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
    MediaArtifactService mediaArtifactService;

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
        try {
            var artifact = mediaArtifactService.ensureStored(job);
            return storedContent(artifact.record());
        } catch (MediaArtifactUnavailableException e) {
            return MediaContentResponses.error(HTTPStatus.GONE, "MEDIA_ARTIFACT_EXPIRED", e.getMessage());
        } catch (MediaArtifactNotReadyException e) {
            return MediaContentResponses.error(HTTPStatus.CONFLICT, "MEDIA_ARTIFACT_NOT_READY", e.getMessage())
                    .header("Retry-After", "2");
        } catch (RuntimeException e) {
            return MediaContentResponses.error(HTTPStatus.SERVICE_UNAVAILABLE, "MEDIA_ARTIFACT_FAILED", e.getMessage());
        }
    }

    private Response storedContent(FileRecord record) {
        var downloadUrl = fileService.downloadUrl(record);
        if (downloadUrl != null) {
            return Response.redirect(downloadUrl, HTTPStatus.TEMPORARY_REDIRECT)
                    .header(HTTPHeaders.CACHE_CONTROL, "no-store");
        }
        var contentType = record.contentType == null ? ContentType.APPLICATION_OCTET_STREAM : ContentType.create(record.contentType, StandardCharsets.UTF_8);
        return Response.bytes(fileService.getBytes(record))
                .contentType(contentType)
                .header(HTTPHeaders.CACHE_CONTROL, "private, max-age=3600");
    }
}
