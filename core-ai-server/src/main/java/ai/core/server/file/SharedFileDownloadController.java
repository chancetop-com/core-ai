package ai.core.server.file;

import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * @author Xander
 */
public class SharedFileDownloadController implements Controller {
    @Inject
    FileService fileService;

    @Override
    public Response execute(Request request) {
        var token = request.pathParam("token");
        var record = fileService.getShared(token);
        var data = fileService.getBytes(record);
        var contentType = record.contentType != null ? ContentType.parse(record.contentType) : ContentType.APPLICATION_OCTET_STREAM;
        return Response.bytes(data).contentType(contentType);
    }
}
