package ai.core.server.file;

import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * @author stephen
 */
public class FileDownloadController implements Controller {
    @Inject
    FileService fileService;

    @Override
    public Response execute(Request request) {
        var id = request.pathParam("id");
        var record = fileService.get(id);
        var data = fileService.getBytes(record);
        var contentType = record.contentType != null ? ContentType.parse(record.contentType) : ContentType.APPLICATION_OCTET_STREAM;
        return Response.bytes(data).contentType(contentType);
    }
}
