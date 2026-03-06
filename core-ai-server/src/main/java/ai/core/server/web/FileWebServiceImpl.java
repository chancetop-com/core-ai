package ai.core.server.web;

import ai.core.api.server.FileWebService;
import ai.core.api.server.file.FileView;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.file.FileService;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class FileWebServiceImpl implements FileWebService {
    @Inject
    WebContext webContext;
    @Inject
    FileService fileService;

    @Override
    public FileView get(String id) {
        var record = fileService.get(id);
        var view = new FileView();
        view.id = record.id;
        view.fileName = record.fileName;
        view.contentType = record.contentType;
        view.size = record.size;
        view.createdAt = record.createdAt;
        return view;
    }

    @Override
    public void delete(String id) {
        var userId = AuthContext.userId(webContext);
        ActionLogContext.put("user_id", userId);
        fileService.delete(id);
    }
}
