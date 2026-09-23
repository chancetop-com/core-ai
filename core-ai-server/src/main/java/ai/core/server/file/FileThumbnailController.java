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
public class FileThumbnailController implements Controller {
    @Inject
    FileService fileService;

    @Override
    public Response execute(Request request) {
        return FileResponseSupport.thumbnail(fileService.get(request.pathParam("id")), fileService);
    }
}
