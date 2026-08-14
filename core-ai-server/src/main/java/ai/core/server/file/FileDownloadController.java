package ai.core.server.file;

import ai.core.server.rbac.PermissionsBypass;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;

/**
 * @author stephen
 */
@PermissionsBypass
public class FileDownloadController implements Controller {
    @Inject
    FileService fileService;

    @Override
    public Response execute(Request request) {
        var id = request.pathParam("id");
        var record = fileService.get(id);
        return FileResponseSupport.content(record, fileService);
    }
}
