package ai.core.server.file;

import ai.core.server.domain.FileRecord;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.http.HTTPHeaders;
import core.framework.web.Response;

/**
 * Builds the download response for a file record: 307 redirect to the pre-signed object storage URL
 * when the content was migrated, otherwise the legacy Mongo base64 payload.
 *
 * @author stephen
 */
final class FileResponseSupport {
    private static final String CACHE_CONTROL = "public, max-age=604800";
    private static final String NO_STORE = "no-store";
    private static final String ETAG = "ETag";

    static Response content(FileRecord record, FileService fileService) {
        var downloadUrl = fileService.downloadUrl(record);
        if (downloadUrl != null) {
            // The pre-signed URL expires (Azure SAS ~1h, MinIO ~1h), so the redirect must never
            // be cached. A cached 307 would keep redirecting to a stale signed URL and fail with
            // 403 after the signature expires.
            return Response.redirect(downloadUrl, HTTPStatus.TEMPORARY_REDIRECT)
                    .header(HTTPHeaders.CACHE_CONTROL, NO_STORE);
        }
        var data = fileService.getBytes(record);
        var contentType = record.contentType != null ? ContentType.parse(record.contentType) : ContentType.APPLICATION_OCTET_STREAM;
        return Response.bytes(data).contentType(contentType)
                .header(HTTPHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(ETAG, etag(record));
    }

    /**
     * Thumbnails are small enough to go through the instance and are cached for a week, so a list is
     * paid for once per browser instead of once per view. Records without one fall back to the original.
     */
    static Response thumbnail(FileRecord record, FileService fileService) {
        var thumbnail = fileService.thumbnail(record);
        if (thumbnail == null) return content(record, fileService);
        return Response.bytes(thumbnail).contentType(ContentType.parse("image/jpeg"))
                .header(HTTPHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(ETAG, thumbnailEtag(record));
    }

    private static String thumbnailEtag(FileRecord record) {
        return "\"" + record.id + "-thumb\"";
    }

    private static String etag(FileRecord record) {
        return "\"" + record.id + "-" + record.size + "\"";
    }
}
