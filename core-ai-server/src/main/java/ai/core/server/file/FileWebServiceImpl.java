package ai.core.server.file;

import ai.core.api.server.file.FileView;
import ai.core.api.server.FileWebService;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class FileWebServiceImpl implements FileWebService {
    @Inject
    FileService fileService;

    @Override
    public FileView get(String id) {
        var record = fileService.get(id);
        var view = new FileView();
        view.id = record.id.toHexString();
        view.fileName = record.fileName;
        view.contentType = record.contentType;
        view.size = record.size;
        view.createdAt = record.createdAt;
        return view;
    }

    @Override
    public void delete(String id) {
        fileService.delete(id);
    }
}
