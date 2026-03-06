package ai.core.server.file;

import ai.core.api.server.file.FileView;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.Controller;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

/**
 * @author stephen
 */
public class FileUploadController implements Controller {
    @Inject
    FileService fileService;

    @Inject
    WebContext webContext;

    @Override
    public Response execute(Request request) {
        var userId = AuthContext.userId(webContext);
        var files = request.files();
        if (files.isEmpty()) {
            throw new BadRequestException("no file uploaded");
        }

        var entry = files.entrySet().iterator().next();
        var multipartFile = entry.getValue();

        var record = fileService.upload(userId, multipartFile.fileName, multipartFile.contentType, multipartFile.path);

        var view = new FileView();
        view.id = record.id;
        view.fileName = record.fileName;
        view.contentType = record.contentType;
        view.size = record.size;
        view.createdAt = record.createdAt;
        return Response.bean(view);
    }
}
