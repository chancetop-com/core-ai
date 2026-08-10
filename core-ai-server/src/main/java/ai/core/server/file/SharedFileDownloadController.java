package ai.core.server.file;

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
        return FileResponseSupport.content(record, fileService);
    }
}
