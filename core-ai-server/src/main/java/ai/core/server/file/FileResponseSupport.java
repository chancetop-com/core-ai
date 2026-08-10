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
    private static final String ETAG = "ETag";

    static Response content(FileRecord record, FileService fileService) {
        var downloadUrl = fileService.downloadUrl(record);
        if (downloadUrl != null) {
            return Response.redirect(downloadUrl, HTTPStatus.TEMPORARY_REDIRECT)
                    .header(HTTPHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .header(ETAG, etag(record));
        }
        var data = fileService.getBytes(record);
        var contentType = record.contentType != null ? ContentType.parse(record.contentType) : ContentType.APPLICATION_OCTET_STREAM;
        return Response.bytes(data).contentType(contentType)
                .header(HTTPHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(ETAG, etag(record));
    }

    private static String etag(FileRecord record) {
        return "\"" + record.id + "-" + record.size + "\"";
    }
}
