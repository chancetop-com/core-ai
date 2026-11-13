package ai.core.server.share;

import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Proxies shared file bytes without issuing independently usable object-storage credentials.
 *
 * @author Xander
 */
public class SessionShareFileController implements Controller {
    private static final Set<String> INLINE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif");
    private final Semaphore downloads = new Semaphore(2);

    @Inject
    SessionShareService shareService;
    @Inject
    SessionShareFileService fileService;

    @Override
    public Response execute(Request request) {
        var share = shareService.requirePublic(request.pathParam("token"));
        var file = fileService.requireFile(share, request.pathParam("refId"));
        if (!downloads.tryAcquire()) {
            return Response.text("Shared downloads are busy. Please retry.").status(HTTPStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5").header("Cache-Control", "no-store");
        }
        try {
            var data = fileService.read(share, file);
            shareService.requirePublic(request.pathParam("token"));
            var inline = INLINE_TYPES.contains(file.contentType == null ? "" : file.contentType);
            var contentType = inline ? ContentType.parse(file.contentType) : ContentType.APPLICATION_OCTET_STREAM;
            var filename = file.fileName == null ? "download" : file.fileName;
            var disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            return Response.bytes(data).contentType(contentType)
                .header("Content-Disposition", disposition)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header("Cache-Control", "no-store");
        } finally {
            downloads.release();
        }
    }
}
